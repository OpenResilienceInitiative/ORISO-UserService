package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import java.time.LocalDateTime;

/**
 * Where an invite stands on the Admin's tracker, and when it reached each step. The one place that
 * derives the progress phase; the Admin's status tiles count it.
 */
public record InviteProgress(
    Phase phase,
    LocalDateTime unitCreatedAt,
    LocalDateTime sentAt,
    LocalDateTime accountCreatedAt,
    LocalDateTime completedAt) {

  public enum Phase {
    /** Stored, not sent: a draft, or waiting for its unit. "Vorbereitet". */
    PREPARED,
    /** The mail went out and the link is valid. "Eingeladen". */
    INVITED,
    /** The account exists, a gate (2FA, for a Träger admin also the DPA) is still open. */
    ACCOUNT_CREATED,
    /** Every gate is passed. "Fertig". */
    DONE,
    /** Stuck until an admin acts: bounced, expired, failed, or no admin for its new unit. */
    NEEDS_ACTION,
    /** Revoked or replaced by a newer invite; no tile. */
    CLOSED
  }

  /**
   * @param latestDelivery the invite's newest mail delivery, or null
   * @param queueProblem {@link UnitQueue#problemOf}, or null
   */
  public static InviteProgress of(
      AccountInvite invite,
      InviteEmailDelivery latestDelivery,
      InviteQueueProblem queueProblem,
      LocalDateTime now) {
    LocalDateTime sentAt =
        latestDelivery != null && latestDelivery.getStatus() == InviteEmailDeliveryStatus.SENT
            ? latestDelivery.getSentAt()
            : null;
    LocalDateTime completedAt = completedAt(invite);
    return new InviteProgress(
        phaseOf(invite, latestDelivery, queueProblem, now),
        invite.getUnitCreatedAt(),
        sentAt,
        invite.getAcceptedAt(),
        completedAt);
  }

  private static Phase phaseOf(
      AccountInvite invite,
      InviteEmailDelivery latestDelivery,
      InviteQueueProblem queueProblem,
      LocalDateTime now) {
    AccountInviteStatus status = invite.getStatus();
    if (status == null) {
      return Phase.PREPARED;
    }
    return switch (status) {
      case WAITING_FOR_UNIT -> queueProblem == null ? Phase.PREPARED : Phase.NEEDS_ACTION;
      case DRAFT -> Phase.PREPARED;
      case EMAIL_SENT ->
          elapsed(invite, now) || bounced(latestDelivery) ? Phase.NEEDS_ACTION : Phase.INVITED;
      case ACCEPTED -> {
        if (invite.getProvisioningStatus() == AccountInviteProvisioningStatus.FAILED) {
          yield Phase.NEEDS_ACTION;
        }
        yield gatesPassed(invite) ? Phase.DONE : Phase.ACCOUNT_CREATED;
      }
      case EXPIRED -> Phase.NEEDS_ACTION;
      case REVOKED, SUPERSEDED -> Phase.CLOSED;
    };
  }

  /** A Träger admin is done only once the DPA is signed, as on the Admin's tenant track. */
  private static boolean gatesPassed(AccountInvite invite) {
    return AccountInviteService.isTwoFactorGateSatisfied(invite.getTwoFactorStatus())
        && (invite.getTargetRole() != AccountInviteTargetRole.TENANT_ADMIN
            || invite.getDpaSignedAt() != null);
  }

  /** Null while a gate is open, and for older rows whose 2FA activation was never dated. */
  private static LocalDateTime completedAt(AccountInvite invite) {
    if (invite.getStatus() != AccountInviteStatus.ACCEPTED || !gatesPassed(invite)) {
      return null;
    }
    LocalDateTime twoFactorDoneAt =
        switch (invite.getTwoFactorStatus()) {
          case ACTIVE -> invite.getTwoFactorActivatedAt();
          case WAIVED -> invite.getTwoFactorWaivedAt();
          case NOT_REQUIRED, DISABLED_BY_POLICY -> invite.getAcceptedAt();
          case PENDING_SETUP -> null;
        };
    if (twoFactorDoneAt == null || invite.getTargetRole() != AccountInviteTargetRole.TENANT_ADMIN) {
      return twoFactorDoneAt;
    }
    return later(twoFactorDoneAt, invite.getDpaSignedAt());
  }

  private static boolean elapsed(AccountInvite invite, LocalDateTime now) {
    return invite.getExpiresAt() != null && !invite.getExpiresAt().isAfter(now);
  }

  private static boolean bounced(InviteEmailDelivery latestDelivery) {
    return latestDelivery != null && latestDelivery.getStatus() == InviteEmailDeliveryStatus.FAILED;
  }

  private static LocalDateTime later(LocalDateTime first, LocalDateTime second) {
    return first.isAfter(second) ? first : second;
  }
}
