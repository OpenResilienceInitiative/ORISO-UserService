package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.RegisterCounsellorCommand;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.RegisterTenantAdminCommand;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression guard for the ONE write both onboarding flows perform while answering with an
 * exception: an overdue link is flipped {@code EMAIL_SENT -> EXPIRED} and the caller still receives
 * the 410 link-death answer. If that write is rolled back with the exception, the row stays {@code
 * EMAIL_SENT} forever and every later visit re-runs the expiry — the link never reaches its
 * terminal state and the audit trail lies about it.
 *
 * <p>Why Spring-backed and not a Mockito test (#1008 review): the guarantee is a TRANSACTION
 * property. A unit test can only see that {@code save(...)} was called, which stays true even when
 * the surrounding transaction rolls the write back. So these tests drive the real services against
 * the real database, and read the invite back in a SEPARATE transaction — only committed state can
 * satisfy them.
 */
@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OnboardingInviteExpiryCommitIT {

  @Autowired private TenantAdminOnboardingService tenantAdminOnboardingService;

  @Autowired private CounsellorOnboardingService counsellorOnboardingService;

  @Autowired private AccountInviteRepository accountInviteRepository;

  @Autowired private PlatformTransactionManager transactionManager;

  private final List<Long> seededInviteIds = new ArrayList<>();

  /**
   * Everything here runs against the REAL database and outside a rolled-back test transaction (both
   * required for the committed-state assertions), so nothing cleans the seed rows up on its own:
   * without this the invite table would grow with every run and any test that counts or lists
   * invites could start failing (#1008 review).
   */
  @AfterEach
  void deleteSeededInvites() {
    seededInviteIds.forEach(accountInviteRepository::deleteById);
    seededInviteIds.clear();
  }

  @Test
  void tenantAdminResolve_expiredInvite_commitsTheExpiredTransitionDespiteTheLinkDeathAnswer() {
    String token = "expiry-commit-tenant-resolve-" + java.util.UUID.randomUUID();
    Long inviteId = seedExpiredInvite(token, AccountInviteTargetRole.TENANT_ADMIN);

    assertThatThrownBy(() -> tenantAdminOnboardingService.resolveOnboardingInvite(token))
        .isInstanceOf(AccountInviteLinkException.class)
        .extracting(exception -> ((AccountInviteLinkException) exception).getReason())
        .isEqualTo(AccountInviteLinkException.Reason.EXPIRED);

    assertThat(committedStatusOf(inviteId)).isEqualTo(AccountInviteStatus.EXPIRED);
  }

  @Test
  void tenantAdminRegister_expiredInvite_commitsTheExpiredTransitionDespiteTheLinkDeathAnswer() {
    String token = "expiry-commit-tenant-register-" + java.util.UUID.randomUUID();
    Long inviteId = seedExpiredInvite(token, AccountInviteTargetRole.TENANT_ADMIN);

    assertThatThrownBy(
            () ->
                tenantAdminOnboardingService.registerTenantAdmin(
                    token, tenantAdminRegistrationCommand()))
        .isInstanceOf(AccountInviteLinkException.class)
        .extracting(exception -> ((AccountInviteLinkException) exception).getReason())
        .isEqualTo(AccountInviteLinkException.Reason.EXPIRED);

    assertThat(committedStatusOf(inviteId)).isEqualTo(AccountInviteStatus.EXPIRED);
  }

  @Test
  void counsellorResolve_expiredInvite_commitsTheExpiredTransitionDespiteTheLinkDeathAnswer() {
    String token = "expiry-commit-counsellor-resolve-" + java.util.UUID.randomUUID();
    Long inviteId = seedExpiredInvite(token, AccountInviteTargetRole.COUNSELLOR);

    assertThatThrownBy(() -> counsellorOnboardingService.resolveOnboardingInvite(token))
        .isInstanceOf(AccountInviteLinkException.class)
        .extracting(exception -> ((AccountInviteLinkException) exception).getReason())
        .isEqualTo(AccountInviteLinkException.Reason.EXPIRED);

    assertThat(committedStatusOf(inviteId)).isEqualTo(AccountInviteStatus.EXPIRED);
  }

  @Test
  void counsellorRegister_expiredInvite_commitsTheExpiredTransitionDespiteTheLinkDeathAnswer() {
    String token = "expiry-commit-counsellor-register-" + java.util.UUID.randomUUID();
    Long inviteId = seedExpiredInvite(token, AccountInviteTargetRole.COUNSELLOR);

    assertThatThrownBy(
            () -> counsellorOnboardingService.registerCounsellor(token, counsellorCommand()))
        .isInstanceOf(AccountInviteLinkException.class)
        .extracting(exception -> ((AccountInviteLinkException) exception).getReason())
        .isEqualTo(AccountInviteLinkException.Reason.EXPIRED);

    assertThat(committedStatusOf(inviteId)).isEqualTo(AccountInviteStatus.EXPIRED);
  }

  /** Reads the persisted status in its OWN transaction — uncommitted work cannot be seen here. */
  private AccountInviteStatus committedStatusOf(Long inviteId) {
    TransactionTemplate separateTransaction = new TransactionTemplate(transactionManager);
    separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return separateTransaction.execute(
        status ->
            accountInviteRepository
                .findById(inviteId)
                .map(AccountInvite::getStatus)
                .orElseThrow(() -> new AssertionError("The seeded invite disappeared")));
  }

  private Long seedExpiredInvite(String rawToken, AccountInviteTargetRole targetRole) {
    Long inviteId =
        accountInviteRepository
            .save(
                AccountInvite.builder()
                    .targetRole(targetRole)
                    .tenantId(79L)
                    .tenantIdReservationToken("3f2c6d1e-8b1a-4b8e-9f47-1234567890ab")
                    .recipientEmail("lisa.simpson@oriso.org")
                    .firstName("Lisa")
                    .lastName("Simpson")
                    .agencyId(275L)
                    .departmentId(2L)
                    .tokenHash(AccountInviteService.hash(rawToken))
                    .expiresAt(LocalDateTime.now().minusDays(1))
                    .status(AccountInviteStatus.EMAIL_SENT)
                    .emailVerificationStatus(EmailVerificationStatus.PENDING)
                    .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
                    .createDate(LocalDateTime.now().minusDays(8))
                    .build())
            .getId();
    seededInviteIds.add(inviteId);
    return inviteId;
  }

  /** Valid input — the registration must be refused for the expiry, not for its payload. */
  private static RegisterTenantAdminCommand tenantAdminRegistrationCommand() {
    return new RegisterTenantAdminCommand(
        "Beratungsstelle Springfield",
        "springfield",
        "Evergreen Terrace 742",
        true,
        "Lisa Simpson",
        "Head of centre",
        "lisa.simpson@oriso.org",
        "Beratungsstelle Springfield",
        "Valid-Test-Password-2026!",
        79L,
        "3f2c6d1e-8b1a-4b8e-9f47-1234567890ab");
  }

  /** Valid input — the registration must be refused for the expiry, not for its payload. */
  private static RegisterCounsellorCommand counsellorCommand() {
    return new RegisterCounsellorCommand(
        "codex_expiry_counsellor",
        "Valid-Test-Password-2026!",
        "counsellor_female",
        "Head of centre",
        null,
        "Lisa",
        "Lisa S. (Nord)",
        List.of(2L));
  }
}
