package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.config.AgencyMembershipBackfillExecutorConfig;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.JoinAttempt;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

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

  private final AgencySilentMembershipService agencySilentMembershipService;
  private final SessionRoomGateway sessionRoomGateway;
  private final MatrixRateLimitPause rateLimitPause;
  private final Executor executor;

  public AgencyDepartmentMembershipBackfill(
      AgencySilentMembershipService agencySilentMembershipService,
      SessionRoomGateway sessionRoomGateway,
      MatrixRateLimitPause rateLimitPause,
      @Qualifier(AgencyMembershipBackfillExecutorConfig.EXECUTOR_BEAN) Executor executor) {
    this.agencySilentMembershipService = agencySilentMembershipService;
    this.sessionRoomGateway = sessionRoomGateway;
    this.rateLimitPause = rateLimitPause;
    this.executor = executor;
  }

  /**
   * Queues the deferred counsellors and returns at once.
   *
   * @param agencyMatrixUserId the agency service account; a fresh token is minted per counsellor,
   *     because a large department can outlive a single server-operation token
   */
  public void schedule(
      Long agencyId, String roomId, String agencyMatrixUserId, List<Consultant> consultants) {
    if (consultants == null || consultants.isEmpty() || isBlank(roomId)) {
      return;
    }
    var pending = List.copyOf(consultants);
    try {
      executor.execute(() -> backfill(agencyId, roomId, agencyMatrixUserId, pending));
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
      Long agencyId, String roomId, String agencyMatrixUserId, List<Consultant> consultants) {
    int joined = 0;
    List<Consultant> givenUp = new ArrayList<>();
    for (Consultant consultant : consultants) {
      if (joinWithRetry(consultant, roomId, agencyMatrixUserId)) {
        joined++;
      } else {
        givenUp.add(consultant);
      }
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

  private boolean joinWithRetry(Consultant consultant, String roomId, String agencyMatrixUserId) {
    long failureBackoffMs = INITIAL_FAILURE_BACKOFF_MS;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      var result = tryJoin(consultant, roomId, agencyMatrixUserId);
      if (result.joined()) {
        return true;
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
        return false;
      }
    }
    return false;
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
