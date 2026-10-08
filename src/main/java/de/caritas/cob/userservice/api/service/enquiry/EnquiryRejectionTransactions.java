package de.caritas.cob.userservice.api.service.enquiry;

import de.caritas.cob.userservice.api.exception.httpresponses.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The two durable phases; protocol traffic is deliberately outside this component. */
@Service
@RequiredArgsConstructor
public class EnquiryRejectionTransactions {
  private final @NonNull SessionRepository sessions;
  private final @NonNull EnquiryRejectionRepository rejections;
  private final @NonNull ConsultantRepository consultants;
  private final @NonNull ConsultantAgencyRepository agencies;
  private final @NonNull UserRepository users;
  private final @NonNull TeamDiscussionRepository discussions;
  private final @NonNull EventNotificationRepository events;
  private final @NonNull AccountInactivityService lifecycle;
  private final @NonNull AuthenticatedUser caller;
  private final @NonNull MatrixFeedUpdateSignalService signal;
  private final @NonNull Clock clock;

  public record Attempt(EnquiryRejection decision, String token) {}

  @Transactional
  public Attempt begin(Long id) {
    if (!caller.isConsultant()) throw new ForbiddenException("Consultant authority required");
    var tenant = TenantContext.getCurrentTenant();
    if (tenant == null || tenant <= 0 || !Objects.equals(tenant, caller.getTenantId()))
      throw new ForbiddenException("Current tenant required");
    var session = lockedSession(id);
    if (!matchesTenant(session.getTenantId(), tenant))
      throw new NotFoundException("Session unavailable");
    var actor =
        consultants
            .findByIdAndDeleteDateIsNull(caller.getUserId())
            .orElseThrow(() -> new ForbiddenException("Current consultant required"));
    if (!Objects.equals(actor.getTenantId(), tenant)
        || !active(actor.getId())
        || session.getAgencyId() == null
        || !agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            actor.getId(), session.getAgencyId()))
      throw new ForbiddenException("Current owning agency authority required");
    var existing = rejections.findForUpdate(id).orElse(null);
    if (existing != null) {
      if (!Objects.equals(existing.getActorId(), actor.getId()))
        throw new ConflictException("Different rejection decision");
      requireBinding(session, existing);
      return claim(existing);
    }
    if (session.getStatus() != Session.SessionStatus.NEW
        || session.getConsultant() != null
        || session.getEnquiryMessageDate() == null
        || session.getRegistrationType() != Session.RegistrationType.REGISTERED
        || (session.getConversationType() != null
            && session.getConversationType() != ConversationType.AGENCY_COUNSELLING)
        || session.getUser() == null)
      throw new ConflictException("Submitted unassigned ordinary enquiry required");
    var decision = new EnquiryRejection();
    decision.setSession(session);
    decision.setSessionId(id);
    decision.setTenantId(tenant);
    decision.setActorId(actor.getId());
    decision.setSeekerId(session.getUser().getUserId());
    decision.setAgencyId(session.getAgencyId());
    decision.setPrimaryRoomId(session.getMatrixRoomId());
    decision.setGeneration(UUID.randomUUID().toString());
    decision.setRejectedAt(now());
    decision.setState(EnquiryRejection.State.PENDING);
    decision.setRecipientOutcome(EnquiryRejection.RecipientOutcome.UNDECIDED);
    decision.setNextAttemptAt(now());
    discussions
        .findBySessionId(id)
        .ifPresent(
            team -> {
              decision.setTeamRoomId(team.getMatrixRoomId());
              team.setStatus(TeamDiscussion.Status.ARCHIVED);
              team.setArchiveDate(decision.getRejectedAt());
              team.setReadOnlyApplied(false);
              discussions.save(team);
            });
    session.setStatus(Session.SessionStatus.REJECTED);
    sessions.save(session);
    return claim(rejections.saveAndFlush(decision));
  }

  @Transactional
  public Attempt resume(Long id) {
    var session = lockedSession(id);
    var decision =
        rejections
            .findForUpdate(id)
            .orElseThrow(() -> new NotFoundException("Rejection unavailable"));
    requireBinding(session, decision);
    return claim(decision);
  }

  private Attempt claim(EnquiryRejection decision) {
    if (decision.getState() == EnquiryRejection.State.CONFIRMED) return new Attempt(decision, null);
    var now = now();
    if ((decision.getClaimUntil() != null && decision.getClaimUntil().isAfter(now))
        || decision.getNextAttemptAt().isAfter(now)) return new Attempt(decision, null);
    decision.setClaimToken(UUID.randomUUID().toString());
    decision.setClaimUntil(now.plusMinutes(2));
    decision.setLastAttemptAt(now);
    decision.setAttemptCount(decision.getAttemptCount() + 1);
    return new Attempt(rejections.saveAndFlush(decision), decision.getClaimToken());
  }

  @Transactional
  public boolean finish(Long id, String generation, String token, boolean verified, String stage) {
    var session = lockedSession(id);
    var decision =
        rejections
            .findForUpdate(id)
            .orElseThrow(() -> new NotFoundException("Rejection unavailable"));
    requireBinding(session, decision);
    if (!Objects.equals(generation, decision.getGeneration()))
      throw new ConflictException("Rejection generation changed");
    if (decision.getState() == EnquiryRejection.State.CONFIRMED) return true;
    if (token == null || !Objects.equals(token, decision.getClaimToken())) return false;
    decision.setClaimUntil(null);
    decision.setClaimToken(null);
    if (!verified) {
      decision.setFailureStage(stage);
      decision.setNextAttemptAt(
          now().plusSeconds(Math.min(3600L, 5L << Math.min(9, decision.getAttemptCount() - 1))));
      rejections.save(decision);
      return false;
    }
    decision.setState(EnquiryRejection.State.CONFIRMED);
    decision.setConfirmedAt(now());
    decision.setFailureStage(null);
    var recipient = users.findByUserIdAndDeleteDateIsNull(decision.getSeekerId()).orElse(null);
    boolean eligible =
        recipient != null
            && Objects.equals(recipient.getTenantId(), decision.getTenantId())
            && active(recipient.getUserId());
    decision.setRecipientOutcome(
        eligible
            ? EnquiryRejection.RecipientOutcome.DELIVERED
            : EnquiryRejection.RecipientOutcome.SUPPRESSED);
    if (eligible) {
      var key = "enquiry-rejected:" + id + ":" + decision.getGeneration();
      if (!events.existsByRecipientUserIdAndDeduplicationKey(decision.getSeekerId(), key)) {
        events.saveAndFlush(
            EventNotification.builder()
                .recipientUserId(decision.getSeekerId())
                .eventType("request.denied")
                .category("system")
                .title("Enquiry declined")
                .text("Your enquiry was declined. You can still read the conversation.")
                .params("{}")
                .sourceSessionId(id)
                .actionPath("/sessions/user/session/" + id)
                .deduplicationKey(key)
                .tenantId(decision.getTenantId())
                .createDate(now())
                .build());
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                try {
                  signal.signalFeedUpdated(decision.getSeekerId());
                } catch (RuntimeException ignored) {
                  /* durable feed remains authoritative */
                }
              }
            });
      }
    }
    discussions
        .findBySessionId(id)
        .ifPresent(
            team -> {
              team.setReadOnlyApplied(true);
              discussions.save(team);
            });
    rejections.save(decision);
    return true;
  }

  /** Defer a stale unclaimed intent so it cannot starve the bounded oldest-first repair batch. */
  @Transactional
  public void deferUnclaimedRepair(Long id, String generation) {
    var decision = rejections.findForUpdate(id).orElse(null);
    if (decision == null
        || decision.getState() != EnquiryRejection.State.PENDING
        || !Objects.equals(decision.getGeneration(), generation)
        || (decision.getClaimUntil() != null && decision.getClaimUntil().isAfter(now()))) return;
    decision.setFailureStage("BINDING");
    decision.setClaimToken(null);
    decision.setClaimUntil(null);
    decision.setNextAttemptAt(now().plusHours(1));
    rejections.save(decision);
  }

  private void requireBinding(Session session, EnquiryRejection decision) {
    var team = discussions.findBySessionId(session.getId()).orElse(null);
    if (session.getStatus() != Session.SessionStatus.REJECTED
        || session.getConsultant() != null
        || !matchesTenant(session.getTenantId(), decision.getTenantId())
        || !Objects.equals(session.getAgencyId(), decision.getAgencyId())
        || session.getUser() == null
        || !Objects.equals(session.getUser().getUserId(), decision.getSeekerId())
        || !Objects.equals(session.getMatrixRoomId(), decision.getPrimaryRoomId())
        || !Objects.equals(team == null ? null : team.getMatrixRoomId(), decision.getTeamRoomId())
        || (team != null && team.getStatus() != TeamDiscussion.Status.ARCHIVED))
      throw new ConflictException("Rejected enquiry decision binding changed");
  }

  private Session lockedSession(Long id) {
    return sessions
        .findByIdForUpdate(id)
        .orElseThrow(() -> new NotFoundException("Session unavailable"));
  }

  private boolean matchesTenant(Long actual, Long expected) {
    return Objects.equals(actual, expected) || (actual == null && Long.valueOf(1).equals(expected));
  }

  private boolean active(String id) {
    return lifecycle
        .snapshot(id)
        .map(a -> a.status() == AccountInactivityService.Status.ACTIVE)
        .orElse(true);
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
