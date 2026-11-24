package de.caritas.cob.userservice.api.workflow.inviteretention.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteProvisioningStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailDeliveryStatus;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The nightly purge removes revoked, expired and replaced invitations, with their mail-delivery
 * rows, once the retention period has passed (ORISO-Admin#1522).
 */
@DataJpaTest
@TestPropertySource(
    properties = {
      "spring.profiles.active=testing",
      "multitenancy.enabled=false",
      "account-invite.retention.days=90",
      "account-invite.retention.batch-size=50"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(InviteRetentionService.class)
class InviteRetentionServiceIT {

  @Autowired private InviteRetentionService retention;
  @Autowired private AccountInviteRepository invites;
  @Autowired private InviteEmailDeliveryRepository deliveries;

  @AfterEach
  void cleanUp() {
    deliveries.deleteAll();
    invites.deleteAll();
  }

  @Test
  void settledInvitesPastRetention_Should_BePurged_WithTheirMailDeliveries() {
    AccountInvite revoked = revoked(daysAgo(100));
    AccountInvite expired = settled(AccountInviteStatus.EXPIRED, daysAgo(100));
    AccountInvite superseded = superseded(daysAgo(100));
    delivery(revoked);
    delivery(revoked);
    delivery(expired);

    int purged = retention.purge();

    assertThat(purged).isEqualTo(3);
    assertThat(invites.findById(revoked.getId())).isEmpty();
    assertThat(invites.findById(expired.getId())).isEmpty();
    assertThat(invites.findById(superseded.getId())).isEmpty();
    assertThat(deliveries.count()).isZero();
  }

  @Test
  void settledInvitesWithinRetention_Should_BeKept() {
    AccountInvite recentlyRevoked = revoked(daysAgo(10));
    AccountInvite recentlyExpired = settled(AccountInviteStatus.EXPIRED, daysAgo(89));
    delivery(recentlyRevoked);

    int purged = retention.purge();

    assertThat(purged).isZero();
    assertThat(invites.findById(recentlyRevoked.getId())).isPresent();
    assertThat(invites.findById(recentlyExpired.getId())).isPresent();
    assertThat(deliveries.count()).isEqualTo(1);
  }

  @Test
  void openAndAcceptedInvites_Should_NeverBePurged_HoweverOld() {
    AccountInvite accepted = settled(AccountInviteStatus.ACCEPTED, daysAgo(400));
    AccountInvite sent = settled(AccountInviteStatus.EMAIL_SENT, daysAgo(400));
    AccountInvite draft = settled(AccountInviteStatus.DRAFT, daysAgo(400));
    AccountInvite waiting = settled(AccountInviteStatus.WAITING_FOR_UNIT, daysAgo(400));

    int purged = retention.purge();

    assertThat(purged).isZero();
    assertThat(invites.count()).isEqualTo(4);
    assertThat(invites.findById(accepted.getId())).isPresent();
    assertThat(invites.findById(sent.getId())).isPresent();
    assertThat(invites.findById(draft.getId())).isPresent();
    assertThat(invites.findById(waiting.getId())).isPresent();
  }

  /** The time a revoke happened counts, not the last unrelated update. */
  @Test
  void aRevokedInvite_Should_BeKeptUntilRetentionAfterItsRevocation() {
    AccountInvite revokedRecently =
        invites.save(row(AccountInviteStatus.REVOKED, daysAgo(200)).revokedAt(daysAgo(30)).build());

    assertThat(retention.purge()).isZero();
    assertThat(invites.findById(revokedRecently.getId())).isPresent();
  }

  @Test
  void aBacklogLargerThanOneBatch_Should_BePurgedInOneRun() {
    for (int i = 0; i < 120; i++) {
      revoked(daysAgo(100));
    }

    assertThat(retention.purge()).isEqualTo(120);
    assertThat(invites.count()).isZero();
  }

  // --- helpers ----------------------------------------------------------------------------------

  private AccountInvite revoked(LocalDateTime when) {
    return invites.save(row(AccountInviteStatus.REVOKED, when).revokedAt(when).build());
  }

  private AccountInvite superseded(LocalDateTime when) {
    return invites.save(row(AccountInviteStatus.SUPERSEDED, when).supersededAt(when).build());
  }

  private AccountInvite settled(AccountInviteStatus status, LocalDateTime lastChange) {
    return invites.save(row(status, lastChange).build());
  }

  private static AccountInvite.AccountInviteBuilder row(
      AccountInviteStatus status, LocalDateTime lastChange) {
    return AccountInvite.builder()
        .targetRole(AccountInviteTargetRole.COUNSELLOR)
        .tenantId(1L)
        .recipientEmail(UUID.randomUUID() + "@example.org")
        .agencyId(1L)
        .status(status)
        .expiresAt(lastChange)
        .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
        .emailVerificationStatus(EmailVerificationStatus.PENDING)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .createDate(lastChange.minusDays(30))
        .updateDate(lastChange);
  }

  private void delivery(AccountInvite invite) {
    deliveries.save(
        InviteEmailDelivery.builder()
            .accountInviteId(invite.getId())
            .templateKind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
            .subjectSnapshot("Einladung")
            .bodySnapshot("body")
            .recipientSnapshot(invite.getRecipientEmail())
            .status(InviteEmailDeliveryStatus.SENT)
            .createDate(invite.getCreateDate())
            .build());
  }

  private static LocalDateTime daysAgo(long days) {
    return LocalDateTime.now().minusDays(days);
  }
}
