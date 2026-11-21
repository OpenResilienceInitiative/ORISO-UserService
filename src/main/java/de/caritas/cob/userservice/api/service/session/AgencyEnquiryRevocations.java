package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRevocationTaskRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository.PublicationRevocationCandidate;
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
import java.util.stream.Collectors;
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

    var recorded =
        tasks.pendingRoomBindings(removed.getId()).stream()
            .map(binding -> new RoomBinding(binding.getSessionId(), binding.getRoomId()))
            .collect(Collectors.toCollection(HashSet::new));
    var pending =
        sessions
            .lockOpenEnquiryBindings(
                removed.getAgencyId(), AgencyOpenEnquiryMembership.OPEN_STATUSES)
            .stream()
            .filter(
                binding ->
                    recorded.add(new RoomBinding(binding.getSessionId(), binding.getRoomId())))
            .map(
                binding ->
                    taskFor(
                        removed.getId(),
                        removed.getAgencyId(),
                        binding.getSessionId(),
                        binding.getRoomId(),
                        consultant.getId(),
                        consultant.getMatrixUserId()))
            .toList();
    if (!pending.isEmpty()) {
      tasks.saveAll(pending);
    }
  }

  /** The publication transaction holds the roster locks for all of these contacts. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void stageDuringPublication(Session session, List<InitialRoomContact> contacts) {
    if (contacts.isEmpty()) {
      return;
    }
    var consultantIds =
        contacts.stream().map(InitialRoomContact::consultantId).collect(Collectors.toSet());
    var removals =
        assignments
            .findPublicationRevocationCandidates(
                session.getAgencyId(), consultantIds, session.getId(), session.getMatrixRoomId())
            .stream()
            .collect(
                Collectors.toMap(
                    PublicationRevocationCandidate::getConsultantId,
                    PublicationRevocationCandidate::getRelationId));
    var pending = new ArrayList<AgencyEnquiryRevocationTask>();
    for (var contact : contacts) {
      // At most one correction per current binding. Keep the first exact Matrix identity.
      var relationId = removals.remove(contact.consultantId());
      if (relationId != null) {
        pending.add(
            taskFor(
                relationId,
                session.getAgencyId(),
                session.getId(),
                session.getMatrixRoomId(),
                contact.consultantId(),
                contact.matrixUserId()));
      }
    }
    if (!pending.isEmpty()) {
      tasks.saveAll(pending);
    }
  }

  private record RoomBinding(Long sessionId, String roomId) {}

  private AgencyEnquiryRevocationTask taskFor(
      Long relationId,
      Long agencyId,
      Long sessionId,
      String roomId,
      String consultantId,
      String memberId) {
    return AgencyEnquiryRevocationTask.builder()
        .consultantAgencyId(relationId)
        .consultantId(consultantId)
        .agencyId(agencyId)
        .sessionId(sessionId)
        .roomId(roomId)
        .memberId(memberId)
        .retryAt(LocalDateTime.now(clock))
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
