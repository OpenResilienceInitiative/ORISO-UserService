package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.config.AgencyMembershipBackfillExecutorConfig;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.JoinAttempt;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Joins the counsellors that the registration request could not join without waiting (see {@link
 * AgencySilentMembershipService#joinAgencyConsultantsWithoutWaiting}) into the enquiry room, off
 * the request thread.
 *
 * <p>Unlike the request, this worker is allowed to wait, and it waits exactly as long as Synapse
 * says: a {@code 429} is retried after the server's own {@code retry_after_ms} (capped at {@link
 * #MAX_RATE_LIMIT_WAIT_MS}), any other failure after a doubling backoff. Counsellors are joined one
 * at a time, because the limit that throttles them is per room.
 *
 * <p>Entitlement is re-checked from the database before every attempt, because minutes can pass
 * between queueing and joining: a counsellor detached from the agency, or an enquiry accepted
 * meanwhile, must not gain membership the at-creation fan-out would no longer grant (ADR-002). The
 * check is repeated after a successful join and the counsellor removed again if it no longer holds,
 * which closes the window against a concurrent revocation in {@link
 * AgencyLateJoinerMembershipService#removeConsultantFromAgencyRooms} without sharing its lease.
 *
 * <p>Observability: every retry is a WARN, the end of each room's backfill is one INFO line, and a
 * counsellor given up on after {@link #MAX_ATTEMPTS} is an ERROR naming them. Pending work lives
 * only in memory; a pod restart drops it, and the counsellors concerned then rely on the
 * history-key transfer described in {@link AgencyLateJoinerMembershipService}.
 */
@Service
@Slf4j
public class AgencyDepartmentMembershipBackfill {

  static final int MAX_ATTEMPTS = 5;
  static final long MAX_RATE_LIMIT_WAIT_MS = 60_000L;
  static final long INITIAL_FAILURE_BACKOFF_MS = 2_000L;

  /** Same scope as the late-joiner backfill: only enquiries the whole department may pick up. */
  private static final List<SessionStatus> OPEN_ENQUIRY_STATES =
      List.of(SessionStatus.NEW, SessionStatus.INITIAL);

  private final AgencySilentMembershipService agencySilentMembershipService;
  private final SessionRoomGateway sessionRoomGateway;
  private final MatrixRateLimitPause rateLimitPause;
  private final Executor executor;
  private final ConsultantRepository consultantRepository;
  private final ConsultantAgencyRepository consultantAgencyRepository;
  private final SessionRepository sessionRepository;

  public AgencyDepartmentMembershipBackfill(
      AgencySilentMembershipService agencySilentMembershipService,
      SessionRoomGateway sessionRoomGateway,
      MatrixRateLimitPause rateLimitPause,
      @Qualifier(AgencyMembershipBackfillExecutorConfig.EXECUTOR_BEAN) Executor executor,
      ConsultantRepository consultantRepository,
      ConsultantAgencyRepository consultantAgencyRepository,
      SessionRepository sessionRepository) {
    this.agencySilentMembershipService = agencySilentMembershipService;
    this.sessionRoomGateway = sessionRoomGateway;
    this.rateLimitPause = rateLimitPause;
    this.executor = executor;
    this.consultantRepository = consultantRepository;
    this.consultantAgencyRepository = consultantAgencyRepository;
    this.sessionRepository = sessionRepository;
  }

  /**
   * Queues the deferred counsellors and returns at once.
   *
   * @param agencyMatrixUserId the agency service account; a fresh token is minted per counsellor,
   *     because a large department can outlive a single server-operation token
   */
  public void schedule(
      Long agencyId,
      Long sessionId,
      String roomId,
      String agencyMatrixUserId,
      List<Consultant> consultants) {
    if (consultants == null || consultants.isEmpty() || isBlank(roomId) || sessionId == null) {
      return;
    }
    var pending = List.copyOf(consultants);
    // The worker re-reads the session to check entitlement, so it must not start before the
    // registration transaction that saved the room id has committed.
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              submit(agencyId, sessionId, roomId, agencyMatrixUserId, pending);
            }
          });
      return;
    }
    submit(agencyId, sessionId, roomId, agencyMatrixUserId, pending);
  }

  private void submit(
      Long agencyId,
      Long sessionId,
      String roomId,
      String agencyMatrixUserId,
      List<Consultant> pending) {
    try {
      executor.execute(() -> backfill(agencyId, sessionId, roomId, agencyMatrixUserId, pending));
      log.info(
          "Queued {} consultants of agency {} for background membership of room {}",
          pending.size(),
          agencyId,
          roomId);
    } catch (RejectedExecutionException ex) {
      log.error(
          "Background membership queue is full; {} consultants of agency {} were NOT joined into room {}: {}",
          pending.size(),
          agencyId,
          roomId,
          ids(pending));
    }
  }

  int backfill(
      Long agencyId,
      Long sessionId,
      String roomId,
      String agencyMatrixUserId,
      List<Consultant> consultants) {
    int joined = 0;
    int skipped = 0;
    List<Consultant> givenUp = new ArrayList<>();
    for (Consultant queued : consultants) {
      var outcome = joinWithRetry(queued, agencyId, sessionId, roomId, agencyMatrixUserId);
      switch (outcome) {
        case JOINED -> joined++;
        case NOT_ENTITLED -> skipped++;
        case GAVE_UP -> givenUp.add(queued);
      }
    }
    if (skipped > 0) {
      log.info(
          "Background membership skipped {} consultants of agency {} for room {}: no longer entitled",
          skipped,
          agencyId,
          roomId);
    }

    if (givenUp.isEmpty()) {
      log.info(
          "Background membership joined all {} deferred consultants of agency {} into room {}",
          joined,
          agencyId,
          roomId);
    } else {
      log.error(
          "Background membership joined {} of {} deferred consultants of agency {} into room {}; gave up on consultants {}",
          joined,
          consultants.size(),
          agencyId,
          roomId,
          ids(givenUp));
    }
    return joined;
  }

  enum Outcome {
    JOINED,
    NOT_ENTITLED,
    GAVE_UP
  }

  private Outcome joinWithRetry(
      Consultant queued, Long agencyId, Long sessionId, String roomId, String agencyMatrixUserId) {
    long failureBackoffMs = INITIAL_FAILURE_BACKOFF_MS;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      var consultant = entitledConsultant(queued.getId(), agencyId, sessionId, roomId);
      if (consultant == null) {
        return Outcome.NOT_ENTITLED;
      }
      var result = tryJoin(consultant, roomId, agencyMatrixUserId);
      if (result.joined()) {
        if (entitledConsultant(consultant.getId(), agencyId, sessionId, roomId) == null) {
          revoke(consultant, roomId, agencyMatrixUserId);
          return Outcome.NOT_ENTITLED;
        }
        return Outcome.JOINED;
      }
      if (attempt == MAX_ATTEMPTS) {
        break;
      }

      long waitMs;
      if (result.rateLimited()) {
        waitMs = Math.min(result.retryAfterMs(), MAX_RATE_LIMIT_WAIT_MS);
      } else {
        waitMs = failureBackoffMs;
        failureBackoffMs *= 2;
      }
      log.warn(
          "Background membership of consultant {} in room {} {}; retrying attempt {} of {} after {} ms",
          consultant.getId(),
          roomId,
          result.rateLimited() ? "was rate limited" : "failed",
          attempt + 1,
          MAX_ATTEMPTS,
          waitMs);
      if (!rateLimitPause.pause(waitMs)) {
        return Outcome.GAVE_UP;
      }
    }
    return Outcome.GAVE_UP;
  }

  /**
   * The counsellor reloaded from the database, or {@code null} once they may no longer be a member:
   * deleted, detached from the agency, or the enquiry is no longer open to the whole department.
   */
  private Consultant entitledConsultant(
      String consultantId, Long agencyId, Long sessionId, String roomId) {
    try {
      var consultant = consultantRepository.findByIdAndDeleteDateIsNull(consultantId);
      if (consultant.isEmpty()
          || !consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
              consultantId, agencyId)) {
        return null;
      }
      var session = sessionRepository.findById(sessionId);
      return session.isPresent() && isOpenToDepartment(session.get(), roomId)
          ? consultant.get()
          : null;
    } catch (RuntimeException ex) {
      log.warn(
          "Could not verify entitlement of consultant {} for room {}; not joining: {}",
          consultantId,
          roomId,
          ex.getMessage());
      return null;
    }
  }

  private static boolean isOpenToDepartment(Session session, String roomId) {
    return OPEN_ENQUIRY_STATES.contains(session.getStatus())
        && session.getConsultant() == null
        && !Boolean.TRUE.equals(session.getIsConsultantDirectlySet())
        && roomId.equals(session.getMatrixRoomId());
  }

  private void revoke(Consultant consultant, String roomId, String agencyMatrixUserId) {
    var agencyToken = sessionRoomGateway.loginAsUser(agencyMatrixUserId);
    var removed =
        !isBlank(agencyToken)
            && sessionRoomGateway.removeUserFromRoom(
                roomId, consultant.getMatrixUserId(), agencyToken);
    if (removed) {
      log.info(
          "Removed consultant {} from room {} again: entitlement ended during background join",
          consultant.getId(),
          roomId);
    } else {
      log.error(
          "Consultant {} joined room {} after their entitlement ended and could not be removed",
          consultant.getId(),
          roomId);
    }
  }

  private JoinAttempt tryJoin(Consultant consultant, String roomId, String agencyMatrixUserId) {
    try {
      var agencyToken = sessionRoomGateway.loginAsUser(agencyMatrixUserId);
      if (isBlank(agencyToken)) {
        log.warn(
            "Could not mint Matrix token for agency service account {}; background membership of room {} cannot proceed",
            agencyMatrixUserId,
            roomId);
        return JoinAttempt.FAILED;
      }
      return agencySilentMembershipService.tryJoinConsultantIntoRoom(
          consultant, roomId, agencyToken);
    } catch (RuntimeException ex) {
      log.warn(
          "Background membership of consultant {} in room {} failed: {}",
          consultant.getId(),
          roomId,
          ex.getMessage());
      return JoinAttempt.FAILED;
    }
  }

  private static List<String> ids(List<Consultant> consultants) {
    return consultants.stream().map(Consultant::getId).toList();
  }
}
