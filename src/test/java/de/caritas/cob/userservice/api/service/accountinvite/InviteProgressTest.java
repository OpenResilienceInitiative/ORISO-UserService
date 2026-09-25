package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.service.accountinvite.InviteProgress.Phase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class InviteProgressTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 12, 0);

  @Test
  void waitingInvite_Should_BePrepared_Or_NeedAction_When_ItsUnitHasNoAdmin() {
    AccountInvite waiting = invite(AccountInviteStatus.WAITING_FOR_UNIT);

    assertThat(InviteProgress.of(waiting, null, null, NOW).phase()).isEqualTo(Phase.PREPARED);
    assertThat(InviteProgress.of(waiting, null, InviteQueueProblem.NO_UNIT_ADMIN, NOW).phase())
        .isEqualTo(Phase.NEEDS_ACTION);
  }

  @Test
  void sentInvite_Should_NeedAction_When_TheMailBouncedOrTheLinkLapsed() {
    AccountInvite sent = invite(AccountInviteStatus.EMAIL_SENT);
    sent.setExpiresAt(NOW.plusDays(1));
    InviteEmailDelivery delivered = delivery(InviteEmailDeliveryStatus.SENT, NOW.minusDays(1));

    InviteProgress invited = InviteProgress.of(sent, delivered, null, NOW);
    assertThat(invited.phase()).isEqualTo(Phase.INVITED);
    assertThat(invited.sentAt()).isEqualTo(NOW.minusDays(1));
    assertThat(
            InviteProgress.of(sent, delivery(InviteEmailDeliveryStatus.FAILED, null), null, NOW)
                .phase())
        .isEqualTo(Phase.NEEDS_ACTION);
    sent.setExpiresAt(NOW.minusMinutes(1));
    assertThat(InviteProgress.of(sent, delivered, null, NOW).phase()).isEqualTo(Phase.NEEDS_ACTION);
  }

  @Test
  void acceptedInvite_Should_BeDone_WithTheActivationDate_When_TwoFactorIsActive() {
    AccountInvite accepted = invite(AccountInviteStatus.ACCEPTED);
    accepted.setAcceptedAt(NOW.minusDays(2));

    InviteProgress pending = InviteProgress.of(accepted, null, null, NOW);
    assertThat(pending.phase()).isEqualTo(Phase.ACCOUNT_CREATED);
    assertThat(pending.accountCreatedAt()).isEqualTo(NOW.minusDays(2));
    assertThat(pending.completedAt()).isNull();

    accepted.setTwoFactorStatus(TwoFactorGateStatus.ACTIVE);
    accepted.setTwoFactorActivatedAt(NOW.minusDays(1));
    InviteProgress done = InviteProgress.of(accepted, null, null, NOW);
    assertThat(done.phase()).isEqualTo(Phase.DONE);
    assertThat(done.completedAt()).isEqualTo(NOW.minusDays(1));
  }

  @Test
  void traegerAdmin_Should_BeDoneOnlyOnceTheDpaIsSigned() {
    AccountInvite accepted = invite(AccountInviteStatus.ACCEPTED);
    accepted.setTargetRole(AccountInviteTargetRole.TENANT_ADMIN);
    accepted.setTwoFactorStatus(TwoFactorGateStatus.WAIVED);
    accepted.setTwoFactorWaivedAt(NOW.minusDays(3));

    assertThat(InviteProgress.of(accepted, null, null, NOW).phase())
        .isEqualTo(Phase.ACCOUNT_CREATED);

    accepted.setDpaSignedAt(NOW.minusDays(1));
    InviteProgress done = InviteProgress.of(accepted, null, null, NOW);
    assertThat(done.phase()).isEqualTo(Phase.DONE);
    assertThat(done.completedAt()).isEqualTo(NOW.minusDays(1));
  }

  @Test
  void revokedOrReplacedInvite_Should_BeClosed_And_ExpiredOne_Should_NeedAction() {
    assertThat(InviteProgress.of(invite(AccountInviteStatus.REVOKED), null, null, NOW).phase())
        .isEqualTo(Phase.CLOSED);
    assertThat(InviteProgress.of(invite(AccountInviteStatus.SUPERSEDED), null, null, NOW).phase())
        .isEqualTo(Phase.CLOSED);
    assertThat(InviteProgress.of(invite(AccountInviteStatus.EXPIRED), null, null, NOW).phase())
        .isEqualTo(Phase.NEEDS_ACTION);
  }

  private static AccountInvite invite(AccountInviteStatus status) {
    return AccountInvite.builder()
        .targetRole(AccountInviteTargetRole.COUNSELLOR)
        .status(status)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .unitCreatedAt(NOW.minusDays(5))
        .build();
  }

  private static InviteEmailDelivery delivery(
      InviteEmailDeliveryStatus status, LocalDateTime sentAt) {
    return InviteEmailDelivery.builder().status(status).sentAt(sentAt).build();
  }
}
