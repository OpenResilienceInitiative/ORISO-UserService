package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomRateLimitedException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixJoinRateLimitedException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixJoinTemporarilyUnavailableException;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomAttempt.InitialContact;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomAttempt.RequesterJoinPlan;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.session.AgencyEnquiryRoomAttempts.RequesterJoinClaim;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.DepartmentFanOut;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.InitialRoomContact;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgencyPreAssignmentRoomService {

  /**
   * How long the registration request may spend joining the department before the rest moves to the
   * background. Registration answers through a 60 s ingress timeout.
   */
  static final Duration DEPARTMENT_IN_REQUEST_BUDGET = Duration.ofSeconds(10);

  /**
   * When the department pass ended on a rate limit, the asker's invite into the same room would
   * meet the same limit; waiting the server's delay first (bounded) lets it land on the first try.
   */
  static final long MAX_WAIT_BEFORE_ASKER_INVITE_MS = 5_000L;

  /**
   * US#1737: right after another enquiry, the agency account's invites still run and Synapse
   * answers the next room creation with 429 for a few seconds. Retry a bounded number of times,
   * each after the server's delay (capped), instead of leaving the enquiry without a room.
   */
  static final int CREATE_ROOM_MAX_ATTEMPTS = 3;

  static final long MAX_WAIT_BEFORE_CREATE_ROOM_RETRY_MS = 5_000L;
  static final long MAX_WAIT_BEFORE_REQUESTER_JOIN_RETRY_MS = 5_000L;

  // Leave 30 seconds of the 60-second ingress window for fresh Matrix credentials,
  // one normal join and the publication transaction after an inline wait.
  private static final Duration LATEST_INLINE_JOIN = Duration.ofSeconds(30);

  /** A repair for the same session is not started again within this time (US#1737). */
  static final Duration REPAIR_COOLDOWN = Duration.ofSeconds(30);

  private final java.util.Set<Long> repairsInFlight =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final java.util.Map<Long, java.time.Instant> lastRepairStart =
      new java.util.concurrent.ConcurrentHashMap<>();
  java.time.Clock clock = java.time.Clock.systemUTC();

  private final @NonNull AgencyMatrixCredentialClient matrixCredentialClient;
  private final @NonNull SessionRoomGateway sessionRoomGateway;
  private final @NonNull SessionService sessionService;
  private final @NonNull AgencyEnquiryRoomPublication publication;
  private final @NonNull AgencySilentMembershipService agencySilentMembershipService;
  private final @NonNull AgencyDepartmentMembershipBackfill departmentMembershipBackfill;
  private final @NonNull MatrixRateLimitPause rateLimitPause;
  private final @NonNull AgencyEnquiryRoomAttempts roomAttempts;

  /**
   * US#1737: provisions the holding room of an open enquiry that a rate-limited creation left
   * without one, when its advice seeker opens it. Guarded so the asker cannot multiply rooms or
   * Matrix calls: one repair per session at a time on this instance, no new start within {@link
   * #REPAIR_COOLDOWN}, and the session is re-read first so a room provisioned meanwhile (another
   * request or pod) is adopted instead of creating a second one.
   */
  public void repairHoldingRoom(Session session, User user) {
    if (session == null
        || user == null
        || session.getId() == null
        || session.getMatrixRoomId() != null) {
      return;
    }
    Long sessionId = session.getId();
    if (!repairsInFlight.add(sessionId)) {
      return;
    }
    try {
      var current = sessionService.getSession(sessionId);
      if (current.isPresent() && current.get().getMatrixRoomId() != null) {
        session.setMatrixRoomId(current.get().getMatrixRoomId());
        return;
      }
      if (resumePreparedRoom(session, user)) {
        return;
      }
      // Checked only while holding the marker: a legacy repair that just finished cannot be
      // followed by a second start inside the cooldown.
      var now = clock.instant();
      lastRepairStart.values().removeIf(started -> started.plus(REPAIR_COOLDOWN).isBefore(now));
      var last = lastRepairStart.get(sessionId);
      if (last != null && last.plus(REPAIR_COOLDOWN).isAfter(now)) {
        return;
      }
      lastRepairStart.put(sessionId, now);
      log.info("Repairing the missing agency holding room of session {}", sessionId);
      createHoldingRoom(session, user);
    } finally {
      repairsInFlight.remove(sessionId);
    }
  }

  public void ensureHoldingRoom(Session session, User user) {
    if (session == null || user == null) {
      return;
    }
    if (session.getId() != null
        && session.getMatrixRoomId() == null
        && session.getAgencyId() != null
        && !isBlank(user.getMatrixUserId())
        && resumePreparedRoom(session, user)) {
      return;
    }
    createHoldingRoom(session, user);
  }

  private boolean resumePreparedRoom(Session session, User user) {
    var recovery = roomAttempts.claimRequesterJoin(session, user);
    recovery.claim().ifPresent(claim -> joinPreparedRoom(session, user, claim, false, 0));
    return recovery.pending();
  }

  private void createHoldingRoom(Session session, User user) {
    long started = System.nanoTime();
    if (session.getMatrixRoomId() != null) {
      log.debug(
          "Session {} already has Matrix room {} configured, skipping agency holding room.",
          session.getId(),
          session.getMatrixRoomId());
      return;
    }

    if (session.getAgencyId() == null) {
      log.debug(
          "Session {} has no agency assigned, skipping Matrix holding room.", session.getId());
      return;
    }

    if (isBlank(user.getMatrixUserId())) {
      log.warn(
          "User {} missing Matrix user ID, skipping agency holding room for session {}",
          user.getUserId(),
          session.getId());
      return;
    }

    Optional<AgencyMatrixCredentialsDTO> credentialsOpt =
        matrixCredentialClient.fetchMatrixCredentials(session.getAgencyId());

    if (credentialsOpt.isEmpty()) {
      log.warn(
          "No Matrix service account available for agency {}. Session {} will not have holding room.",
          session.getAgencyId(),
          session.getId());
      return;
    }

    AgencyMatrixCredentialsDTO credentials = credentialsOpt.get();

    if (isBlank(credentials.getMatrixUserId())) {
      log.warn(
          "Matrix service account configuration incomplete for agency {}. Skipping holding room for session {}.",
          session.getAgencyId(),
          session.getId());
      return;
    }

    String agencyToken = sessionRoomGateway.loginAsUser(credentials.getMatrixUserId());

    if (isBlank(agencyToken)) {
      log.error(
          "Failed to login Matrix service account {} for agency {}. Holding room skipped for session {}.",
          credentials.getMatrixUserId(),
          session.getAgencyId(),
          session.getId());
      return;
    }

    String roomAlias = buildRoomAlias(session.getId());
    String roomName = buildRoomName(session, credentials.getMatrixUserId());

    try {
      String roomId = createRoomWithRateLimitRetry(roomName, roomAlias, agencyToken);
      if (isBlank(roomId)) {
        log.error("Matrix create room returned empty body for session {}", session.getId());
        return;
      }

      var attemptId = roomAttempts.recordCreated(session, roomId);

      // Order is load-bearing (ADR-002 §1, #199): the department joins while the asker is not a
      // member yet. The asker's Matrix client discovers the room via /sync the moment they are
      // invited — independent of when this method returns — so inviting the asker first opens a
      // window in which their client can snapshot the membership list and encrypt the first
      // message for an audience that does not yet contain the department. Consultants-first makes
      // every membership view the asker's client can ever observe already include the department.
      //
      // Only the counsellors Synapse admits without waiting join here; the rest are joined in the
      // background right after the asker (see AgencyDepartmentMembershipBackfill). Holding the
      // request open for the whole department ran into the 60 s ingress timeout on Dev.
      var departmentFanOut = joinAgencyConsultants(session, roomId, agencyToken);
      if (departmentFanOut.retryAfterMs() > 0) {
        rateLimitPause.pause(
            Math.min(departmentFanOut.retryAfterMs(), MAX_WAIT_BEFORE_ASKER_INVITE_MS));
      }
      roomAttempts.markRequesterInvitationStarted(attemptId);
      if (!inviteUser(roomId, user, agencyToken)) {
        log.error(
            "Asker {} could not be invited to Matrix holding room {} for session {}; room remains unbound.",
            user.getUserId(),
            roomId,
            session.getId());
        return;
      }

      var plan =
          new RequesterJoinPlan(
              user.getUserId(),
              user.getMatrixUserId(),
              credentials.getMatrixUserId(),
              Boolean.TRUE.equals(session.getIsConsultantDirectlySet()),
              departmentFanOut.contacts().stream()
                  .map(
                      contact -> new InitialContact(contact.consultantId(), contact.matrixUserId()))
                  .toList(),
              departmentFanOut.deferred().stream().map(Consultant::getId).toList());
      var claim = roomAttempts.recordConfirmedInvitation(attemptId, plan);
      joinPreparedRoom(session, user, claim, true, started);
    } catch (MatrixCreateRoomException ex) {
      log.error(
          "Could not create agency holding room for session {}: {}",
          session.getId(),
          ex.getMessage());
    }
  }

  private String createRoomWithRateLimitRetry(String roomName, String roomAlias, String token)
      throws MatrixCreateRoomException {
    for (int attempt = 1; ; attempt++) {
      try {
        return sessionRoomGateway.createRoom(roomName, roomAlias, token);
      } catch (MatrixCreateRoomRateLimitedException limited) {
        if (attempt >= CREATE_ROOM_MAX_ATTEMPTS) {
          throw limited;
        }
        long waitMs =
            Math.min(Math.max(limited.getRetryAfterMs(), 0L), MAX_WAIT_BEFORE_CREATE_ROOM_RETRY_MS);
        log.warn(
            "Agency holding room creation was rate limited; retrying attempt {} of {} after {} ms",
            attempt + 1,
            CREATE_ROOM_MAX_ATTEMPTS,
            waitMs);
        if (!rateLimitPause.pause(waitMs)) {
          // Interrupted (shutdown, cancelled request): stop instead of provisioning further.
          throw limited;
        }
      }
    }
  }

  /**
   * FE#811 / ADR-002 §1: the agency's counsellors become real room members here, while the room is
   * still empty, so an enquiry is readable to everyone entitled to pick it up and no one ever joins
   * after a Megolm session was already handed out.
   *
   * <p>A directly addressed enquiry (public counsellor link, appointment booking) is deliberately
   * excluded — the advice seeker chose one counsellor, and fanning that case out to the whole
   * department would widen its audience beyond what they consented to.
   *
   * <p>Department membership is a best-effort side effect, never a precondition (see {@link
   * AgencySilentMembershipService#joinAgencyConsultants}): since it now runs before the asker is
   * invited, an unexpected failure here must not abort the asker's own invite/join or the room
   * persist — otherwise a single consultant-lookup hiccup would kill the whole enquiry.
   */
  private DepartmentFanOut joinAgencyConsultants(
      Session session, String roomId, String agencyToken) {
    if (Boolean.TRUE.equals(session.getIsConsultantDirectlySet())) {
      log.debug(
          "Session {} is directly assigned to a consultant, skipping department membership.",
          session.getId());
      return DepartmentFanOut.NONE;
    }

    try {
      var fanOut =
          agencySilentMembershipService.joinAgencyConsultantsWithoutWaiting(
              session.getAgencyId(), roomId, agencyToken, DEPARTMENT_IN_REQUEST_BUDGET);
      return fanOut == null ? DepartmentFanOut.NONE : fanOut;
    } catch (RuntimeException ex) {
      log.error(
          "Department membership for room {} of session {} failed; continuing with asker-only room: {}",
          roomId,
          session.getId(),
          ex.getMessage());
      return DepartmentFanOut.NONE;
    }
  }

  private boolean inviteUser(String roomId, User user, String agencyToken) {
    try {
      sessionRoomGateway.inviteUser(roomId, user.getMatrixUserId(), agencyToken);
      return true;
    } catch (MatrixInviteUserException ex) {
      log.error(
          "Failed to invite user {} to holding room {}: {}",
          user.getUserId(),
          roomId,
          ex.getMessage());
      return false;
    }
  }

  private void joinPreparedRoom(
      Session session, User user, RequesterJoinClaim claim, boolean firstJoin, long started) {
    var token = sessionRoomGateway.loginAsUser(claim.plan().requesterMatrixUserId());
    if (!firstJoin && isBlank(token)) {
      roomAttempts.deferRequesterJoin(claim, REPAIR_COOLDOWN.toMillis());
      return;
    }

    try {
      // Preserve an invitation-only first request when no requester access token is available.
      if (!isBlank(token) && !sessionRoomGateway.joinRoomOnce(claim.roomId(), token)) {
        roomAttempts.finishRequesterJoin(claim);
        log.warn("Could not join the prepared holding room for session {}", session.getId());
        return;
      }
    } catch (MatrixJoinRateLimitedException e) {
      long waitMs = e.getRetryAfterMs();
      roomAttempts.deferRequesterJoin(claim, waitMs);
      log.info("Holding-room join deferred by Synapse for session {}", session.getId());
      if (firstJoin
          && waitMs <= MAX_WAIT_BEFORE_REQUESTER_JOIN_RETRY_MS
          && System.nanoTime() - started <= LATEST_INLINE_JOIN.minusMillis(waitMs).toNanos()
          && rateLimitPause.pause(waitMs)) {
        roomAttempts
            .claimRequesterJoin(session, user)
            .claim()
            .ifPresent(nextClaim -> joinPreparedRoom(session, user, nextClaim, false, 0));
      }
      return;
    } catch (MatrixJoinTemporarilyUnavailableException e) {
      roomAttempts.deferRequesterJoin(claim, REPAIR_COOLDOWN.toMillis());
      log.info(
          "Holding-room join deferred after a temporary Matrix failure for session {}",
          session.getId());
      return;
    }

    var plan = claim.plan();
    var contacts =
        plan.contacts().stream()
            .map(contact -> new InitialRoomContact(contact.consultantId(), contact.matrixUserId()))
            .toList();
    var binding = publication.publishPrepared(session, claim, contacts);
    if (binding.isEmpty()) {
      roomAttempts.finishRequesterJoin(claim);
      log.warn(
          "Publication of the prepared holding room was refused for session {}", session.getId());
      return;
    }
    var confirmedRoomId = binding.get();
    session.setMatrixRoomId(confirmedRoomId);
    if (!claim.roomId().equals(confirmedRoomId)) {
      roomAttempts.finishRequesterJoin(claim);
      log.warn(
          "An existing room binding superseded the prepared room for session {}", session.getId());
      return;
    }

    log.info(
        "Configured agency holding room for session {} (agency {}).",
        session.getId(),
        session.getAgencyId());
    departmentMembershipBackfill.scheduleConsultantIds(
        session.getAgencyId(),
        session.getId(),
        claim.roomId(),
        plan.agencyMatrixUserId(),
        plan.deferredConsultantIds());
  }

  private String buildRoomAlias(Long sessionId) {
    return "agency_hold_" + sessionId + "_" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String buildRoomName(Session session, String matrixUserId) {
    return String.format("Agency %s pre-assignment #%d", matrixUserId, session.getId());
  }
}
