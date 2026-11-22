package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomAttempt;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomAttempt.RequesterJoinPlan;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRoomAttemptRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgencyEnquiryRoomAttempts {

  private static final Duration JOIN_LEASE = Duration.ofSeconds(30);
  private static final LocalDateTime LATEST_JOIN_READY_AT =
      LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000);

  private final AgencyEnquiryRoomAttemptRepository attempts;
  private final SessionRepository sessions;
  private final Clock clock;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Long recordCreated(Session session, String roomId) {
    return attempts
        .save(new AgencyEnquiryRoomAttempt(session, roomId, LocalDateTime.now(clock)))
        .getId();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markRequesterInvitationStarted(Long attemptId) {
    attempts
        .findById(attemptId)
        .orElseThrow(() -> new IllegalStateException("Holding room attempt is missing"))
        .markRequesterInvitationStarted();
  }

  /**
   * Persist a confirmed invitation and lease the first join before doing any further Matrix I/O.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RequesterJoinClaim recordConfirmedInvitation(Long attemptId, RequesterJoinPlan plan) {
    var attempt = attempts.lockAttempt(attemptId).orElseThrow();
    if (!attempt.isRequesterInvitationStarted() || attempt.getRequesterJoinPlan() != null) {
      throw new IllegalStateException("Holding-room invitation cannot enter the join phase");
    }
    var claim = UUID.randomUUID().toString();
    attempt.recordRequesterJoin(plan, claim, LocalDateTime.now(clock).plus(JOIN_LEASE));
    return new RequesterJoinClaim(attempt.getId(), attempt.getRoomId(), claim, plan);
  }

  /**
   * Serialize competing reopens against the current session. A pending but not due attempt
   * deliberately suppresses the legacy fallback that would create another room.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RequesterJoinRecovery claimRequesterJoin(Session expected, User requester) {
    var current = sessions.lockFresh(expected.getId()).orElse(null);
    if (current == null
        || current.getMatrixRoomId() != null
        || (current.getStatus() != Session.SessionStatus.INITIAL
            && current.getStatus() != Session.SessionStatus.NEW)
        || current.getOwnershipRevision() != expected.getOwnershipRevision()
        || !Objects.equals(current.getTenantId(), expected.getTenantId())
        || !Objects.equals(current.getAgencyId(), expected.getAgencyId())
        || current.getUser() == null
        || !Objects.equals(current.getUser().getUserId(), requester.getUserId())
        || !Objects.equals(current.getUser().getMatrixUserId(), requester.getMatrixUserId())) {
      return RequesterJoinRecovery.WAITING;
    }

    var existing =
        attempts
            .findAllBySessionIdAndRequesterJoinPlanIsNotNullOrderByIdAsc(current.getId())
            .stream()
            .filter(attempt -> attempt.matchesPreparation(current))
            .findFirst();
    if (existing.isEmpty()) {
      return RequesterJoinRecovery.NONE;
    }

    var attempt = existing.orElseThrow();
    var plan = attempt.getRequesterJoinPlan();
    var now = LocalDateTime.now(clock);
    if (!Objects.equals(plan.requesterId(), requester.getUserId())
        || !Objects.equals(plan.requesterMatrixUserId(), requester.getMatrixUserId())
        || plan.directlyAssigned() != Boolean.TRUE.equals(current.getIsConsultantDirectlySet())
        || attempt.getRequesterJoinReadyAt().isAfter(now)) {
      return RequesterJoinRecovery.WAITING;
    }

    var claim = UUID.randomUUID().toString();
    attempt.claimRequesterJoin(claim, now.plus(JOIN_LEASE));
    return new RequesterJoinRecovery(
        true,
        Optional.of(new RequesterJoinClaim(attempt.getId(), attempt.getRoomId(), claim, plan)));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deferRequesterJoin(RequesterJoinClaim claim, long serverDelayMs) {
    attempts
        .lockAttempt(claim.attemptId())
        .filter(attempt -> claim.token().equals(attempt.getRequesterJoinClaim()))
        .ifPresent(
            attempt -> {
              var now = LocalDateTime.now(clock);
              var delay = Duration.ofMillis(serverDelayMs);
              // Keep the same room pending when the wait exceeds the DATETIME(6) range.
              var readyAt =
                  delay.compareTo(Duration.between(now, LATEST_JOIN_READY_AT)) > 0
                      ? LATEST_JOIN_READY_AT
                      : now.plus(delay);
              attempt.deferRequesterJoin(readyAt);
            });
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finishRequesterJoin(RequesterJoinClaim claim) {
    attempts
        .lockAttempt(claim.attemptId())
        .filter(attempt -> claim.token().equals(attempt.getRequesterJoinClaim()))
        .ifPresent(AgencyEnquiryRoomAttempt::finishRequesterJoin);
  }

  /** Called with the current session already locked in the publication transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean ownsRequesterJoin(Session current, String roomId, RequesterJoinClaim claim) {
    return roomId.equals(claim.roomId())
        && claim.plan().directlyAssigned()
            == Boolean.TRUE.equals(current.getIsConsultantDirectlySet())
        && attempts
            .lockAttempt(claim.attemptId())
            .filter(attempt -> attempt.matchesPreparation(current))
            .filter(attempt -> roomId.equals(attempt.getRoomId()))
            .filter(attempt -> claim.token().equals(attempt.getRequesterJoinClaim()))
            .isPresent();
  }

  public record RequesterJoinClaim(
      Long attemptId, String roomId, String token, RequesterJoinPlan plan) {}

  public record RequesterJoinRecovery(boolean pending, Optional<RequesterJoinClaim> claim) {
    private static final RequesterJoinRecovery NONE =
        new RequesterJoinRecovery(false, Optional.empty());
    private static final RequesterJoinRecovery WAITING =
        new RequesterJoinRecovery(true, Optional.empty());
  }

  /** Retire only the exact attempt in the transaction that confirms its binding. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void completePublication(Session confirmed) {
    var matching =
        attempts
            .findAllBySessionIdAndRoomId(confirmed.getId(), confirmed.getMatrixRoomId())
            .stream()
            .filter(attempt -> attempt.matchesBinding(confirmed))
            .toList();
    attempts.deleteAll(matching);
  }
}
