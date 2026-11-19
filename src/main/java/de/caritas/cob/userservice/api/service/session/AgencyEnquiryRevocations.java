package de.caritas.cob.userservice.api.service.session;

import static de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask.WAITING_FOR_ROOM_DECISION;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision.Outcome;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRevocationTaskRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.InitialRoomContact;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles open-enquiry room access with the persisted agency assignment. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgencyEnquiryRevocations {

  static final Duration RETRY_DELAY = Duration.ofMinutes(1);
  static final int BATCH_SIZE = 8;
  private static final Duration BATCH_BUDGET = Duration.ofSeconds(30);

  private final AgencyEnquiryRevocationTaskRepository tasks;
  private final ConsultantAgencyRepository assignments;
  private final ConsultantRepository consultants;
  private final SessionRepository sessions;
  private final AgencyEnquiryRoomRetirements retiredRooms;
  private final AgencyEnquiryRoomDecisions roomDecisions;
  private final AgencyMatrixCredentialClient agencyCredentials;
  private final SessionRoomGateway rooms;
  private final PlatformTransactionManager transactionManager;
  private final Clock clock;

  /** The pending Matrix work commits or rolls back with the database detach. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void stage(ConsultantAgency removed) {
    var consultant = removed.getConsultant();
    if (consultant == null || isBlank(consultant.getMatrixUserId())) {
      return;
    }

    // Record the departure before the session query can wait on an accepting transaction.
    Long departureOrder = removed.getTenantId() == null ? null : roomDecisions.nextEvent();
    var recorded = new HashSet<>(tasks.pendingSessionIds(removed.getId()));
    var pending = new ArrayList<AgencyEnquiryRevocationTask>();
    for (var binding :
        sessions.lockOpenEnquiryBindings(
            removed.getAgencyId(), AgencyOpenEnquiryMembership.OPEN_STATUSES)) {
      if (recorded.add(binding.getSessionId())) {
        pending.add(taskFor(removed, binding.getSessionId(), binding.getRoomId(), null));
      }
    }
    if (departureOrder != null) {
      for (var decision : roomDecisions.affectedBy(removed, departureOrder)) {
        if (recorded.add(decision.getSessionId())) {
          pending.add(taskFor(removed, decision.getSessionId(), decision.getRoomId(), decision));
        }
      }
    }
    if (!pending.isEmpty()) {
      tasks.saveAll(pending);
    }
  }

  /** The publication transaction holds the roster locks for all of these contacts. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void stageDuringPublication(Session session, List<InitialRoomContact> contacts) {
    for (var contact : contacts) {
      if (assignments.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
          contact.consultantId(), session.getAgencyId())) {
        continue;
      }
      assignments
          .findFirstByConsultantIdAndAgencyIdAndDeleteDateIsNotNullOrderByDeleteDateDescIdDesc(
              contact.consultantId(), session.getAgencyId())
          .filter(removed -> !tasks.pendingSessionIds(removed.getId()).contains(session.getId()))
          .ifPresent(
              removed ->
                  tasks.save(
                      buildTask(
                          removed,
                          session.getId(),
                          session.getMatrixRoomId(),
                          contact.consultantId(),
                          contact.matrixUserId(),
                          null,
                          LocalDateTime.now(clock))));
    }
  }

  private AgencyEnquiryRevocationTask taskFor(
      ConsultantAgency removed, Long sessionId, String roomId, AgencyEnquiryRoomDecision decision) {
    var retryAt =
        decision != null && decision.getOutcome() == Outcome.PENDING
            ? WAITING_FOR_ROOM_DECISION
            : LocalDateTime.now(clock);
    return buildTask(
        removed,
        sessionId,
        roomId,
        removed.getConsultant().getId(),
        removed.getConsultant().getMatrixUserId(),
        decision == null ? null : decision.getId(),
        retryAt);
  }

  private AgencyEnquiryRevocationTask buildTask(
      ConsultantAgency removed,
      Long sessionId,
      String roomId,
      String consultantId,
      String memberId,
      Long roomDecisionId,
      LocalDateTime retryAt) {
    return AgencyEnquiryRevocationTask.builder()
        .consultantAgencyId(removed.getId())
        .consultantId(consultantId)
        .agencyId(removed.getAgencyId())
        .sessionId(sessionId)
        .roomId(roomId)
        .memberId(memberId)
        .roomDecisionId(roomDecisionId)
        .retryAt(retryAt)
        .build();
  }

  public void retryDue() {
    long startedAt = System.nanoTime();
    Map<Long, Optional<String>> agencyTokens = new HashMap<>();
    for (var id : tasks.dueIds(LocalDateTime.now(clock), PageRequest.of(0, BATCH_SIZE))) {
      if (System.nanoTime() - startedAt >= BATCH_BUDGET.toNanos()) {
        break;
      }
      attemptSafely(id, agencyTokens);
    }
  }

  private void attemptSafely(Long taskId, Map<Long, Optional<String>> agencyTokens) {
    try {
      var snapshot = tasks.findById(taskId).orElse(null);
      if (snapshot == null) {
        return;
      }
      // Resolve the agency operator outside both critical sections.
      var token =
          agencyTokens
              .computeIfAbsent(
                  snapshot.getAgencyId(), agencyId -> Optional.ofNullable(agencyToken(agencyId)))
              .orElse(null);
      var transaction = new TransactionTemplate(transactionManager);
      transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      transaction.executeWithoutResult(status -> attemptLocked(snapshot, token));
    } catch (RuntimeException failure) {
      log.warn(
          "Failed to reconcile open-enquiry access revocation task {} ({})",
          taskId,
          failure.getClass().getSimpleName());
    }
  }

  private void attemptLocked(AgencyEnquiryRevocationTask snapshot, String token) {
    // Roster changes take this lock too. A re-grant either precedes this decision or waits for the
    // kick to finish, then its normal post-commit membership sync runs.
    consultants.lockConsultant(snapshot.getConsultantId());
    var task = tasks.findByIdForUpdate(snapshot.getId()).orElse(null);
    if (task == null || task.getRetryAt().isAfter(LocalDateTime.now(clock))) {
      return;
    }
    if (assignments.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
        task.getConsultantId(), task.getAgencyId())) {
      tasks.delete(task);
      return;
    }
    // The original room may have been accepted while a kick was pending. An accepted room keeps
    // silent members but drops the agency operator. Never remove the case owner in that room.
    var session = sessions.findByIdForUpdate(task.getSessionId()).orElse(null);
    if (session == null || !Objects.equals(task.getAgencyId(), session.getAgencyId())) {
      retryUnlessConfirmedOut(task);
      return;
    }
    if (session.getConsultant() != null
        && task.getConsultantId().equals(session.getConsultant().getId())) {
      tasks.delete(task);
      return;
    }

    boolean sameRoom = Objects.equals(task.getRoomId(), session.getMatrixRoomId());
    if (task.getRoomDecisionId() != null && !decisionAllowsRevocation(task, session, sameRoom)) {
      return;
    }
    if (!sameRoom && !retiredRooms.provesRetirement(task, session)) {
      retryUnlessConfirmedOut(task);
      return;
    }

    var open =
        sameRoom && AgencyOpenEnquiryMembership.isOpenToDepartment(session, task.getAgencyId());
    if (matrixConfirmedOut(task, token, open)) {
      tasks.delete(task);
    } else {
      defer(task);
    }
  }

  private boolean decisionAllowsRevocation(
      AgencyEnquiryRevocationTask task, Session current, boolean sameRoom) {
    var decision = roomDecisions.forTask(task, current).orElse(null);
    if (decision == null) {
      retryUnlessConfirmedOut(task);
      return false;
    }
    return switch (decision.getOutcome()) {
      case REOPENED -> true;
      case REPLACED -> {
        if (sameRoom) {
          retryUnlessConfirmedOut(task);
        }
        yield !sameRoom;
      }
      case REUSED -> {
        tasks.delete(task);
        yield false;
      }
      case PENDING -> {
        if (decision.matches(current)) {
          defer(task);
        } else {
          retryUnlessConfirmedOut(task);
        }
        yield false;
      }
    };
  }

  private void retryUnlessConfirmedOut(AgencyEnquiryRevocationTask task) {
    try {
      if (rooms.isConfirmedOutOfRoom(task.getRoomId(), task.getMemberId())) {
        tasks.delete(task);
        return;
      }
    } catch (RuntimeException failure) {
      log.warn("Could not verify absence for enquiry revocation task {}", task.getId());
    }
    defer(task);
  }

  private void defer(AgencyEnquiryRevocationTask task) {
    task.retryAt(LocalDateTime.now(clock).plus(RETRY_DELAY));
    log.warn("Open-enquiry access revocation task {} remains due", task.getId());
  }

  private boolean matrixConfirmedOut(AgencyEnquiryRevocationTask task, String token, boolean open) {
    try {
      if (open
          && !isBlank(token)
          && rooms.removeUserFromRoom(task.getRoomId(), task.getMemberId(), token)) {
        return true;
      }
      if (rooms.isConfirmedOutOfRoom(task.getRoomId(), task.getMemberId())) {
        return true;
      }
      if (open) {
        return false;
      }
      var memberToken = rooms.loginAsUser(task.getMemberId());
      if (!isBlank(memberToken)) {
        rooms.leaveRoom(task.getRoomId(), memberToken);
      }
      return rooms.isConfirmedOutOfRoom(task.getRoomId(), task.getMemberId());
    } catch (RuntimeException failure) {
      log.warn(
          "Matrix could not confirm revocation task {} ({})",
          task.getId(),
          failure.getClass().getSimpleName());
      return false;
    }
  }

  private String agencyToken(Long agencyId) {
    try {
      return agencyCredentials
          .fetchMatrixCredentials(agencyId)
          .filter(credentials -> !isBlank(credentials.getMatrixUserId()))
          .map(credentials -> rooms.loginAsUser(credentials.getMatrixUserId()))
          .orElse(null);
    } catch (RuntimeException failure) {
      log.warn(
          "Could not resolve a Matrix operator for enquiry revocation ({})",
          failure.getClass().getSimpleName());
      return null;
    }
  }
}
