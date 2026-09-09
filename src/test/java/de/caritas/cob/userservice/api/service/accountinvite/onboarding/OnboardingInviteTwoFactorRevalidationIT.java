package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Time-of-check/time-of-use guard for the two-factor gate of both onboarding flows (#1030 review).
 *
 * <p>Both flows check the invite's eligibility in one short locked transaction, then RELEASE the
 * lock for the Keycloak round trip that verifies the one-time password, then activate the gate in a
 * second locked transaction. Between check and write the invite can be revoked, run past its
 * expiry, have its gate activated by another path, be re-accepted by a different acceptor, or have
 * its pending secret rotated. Activating the gate anyway would honour a code that was verified
 * against state which no longer exists — so the write re-validates the freshly locked row first.
 *
 * <p>The race is made deterministic exactly as in {@link OnboardingInviteTargetedUpdateIT}: the
 * competing change commits in its own {@code REQUIRES_NEW} transaction INSIDE the stubbed Keycloak
 * verification, i.e. precisely between the check and the write. Each test then reads the committed
 * row and asserts that the gate stayed closed AND that the flow wrote nothing (the pending secret
 * still holds whatever the competing writer left there). Removing the revalidation makes every test
 * here fail.
 *
 * <p>Runs against the real database and outside a rolled-back test transaction, because only
 * committed state can prove the property; the seeded rows are removed in {@link
 * #deleteSeededInvites()}.
 */
@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OnboardingInviteTwoFactorRevalidationIT {

  private static final String VERIFIED_SECRET = "PENDINGSECRET";
  private static final String ROTATED_SECRET = "ROTATEDSECRET";
  private static final String ONE_TIME_PASSWORD = "123456";

  @Autowired private CounsellorOnboardingService counsellorOnboardingService;

  @Autowired private TenantAdminOnboardingService tenantAdminOnboardingService;

  @Autowired private AccountInviteRepository accountInviteRepository;

  @Autowired private PlatformTransactionManager transactionManager;

  /**
   * The single {@code keycloakService} bean implements ALL identity ports, so the concrete bean is
   * mocked — mocking one port interface would replace the bean and break the others at startup.
   */
  @MockitoBean private KeycloakService keycloakService;

  private final List<Long> seededInviteIds = new ArrayList<>();

  @AfterEach
  void deleteSeededInvites() {
    seededInviteIds.forEach(accountInviteRepository::deleteById);
    seededInviteIds.clear();
  }

  // --- counsellor onboarding ---

  @Test
  void counsellorActivateTwoFactor_revokedDuringVerification_answersRevokedAndLeavesGateClosed() {
    Seed seed = seedResumableInvite(AccountInviteTargetRole.COUNSELLOR);
    concurrentlyDuringVerification(seed, row -> row.setStatus(AccountInviteStatus.REVOKED));

    assertLinkDeath(
        () -> counsellorOnboardingService.activateTwoFactor(seed.token(), ONE_TIME_PASSWORD),
        AccountInviteLinkException.Reason.REVOKED);

    assertRowUntouched(seed, AccountInviteStatus.REVOKED, TwoFactorGateStatus.PENDING_SETUP);
  }

  @Test
  void counsellorActivateTwoFactor_expiredDuringVerification_answersConsumedAndLeavesGateClosed() {
    Seed seed = seedResumableInvite(AccountInviteTargetRole.COUNSELLOR);
    concurrentlyDuringVerification(
        seed, row -> row.setExpiresAt(LocalDateTime.now().minusMinutes(5)));

    assertLinkDeath(
        () -> counsellorOnboardingService.activateTwoFactor(seed.token(), ONE_TIME_PASSWORD),
        AccountInviteLinkException.Reason.CONSUMED);

    assertRowUntouched(seed, AccountInviteStatus.ACCEPTED, TwoFactorGateStatus.PENDING_SETUP);
  }

  @Test
  void
      counsellorActivateTwoFactor_gateActivatedDuringVerification_answersConsumedAndWritesNothing() {
    Seed seed = seedResumableInvite(AccountInviteTargetRole.COUNSELLOR);
    concurrentlyDuringVerification(seed, row -> row.setTwoFactorStatus(TwoFactorGateStatus.ACTIVE));

    assertLinkDeath(
        () -> counsellorOnboardingService.activateTwoFactor(seed.token(), ONE_TIME_PASSWORD),
        AccountInviteLinkException.Reason.CONSUMED);

    // The gate was activated by the other writer, not by this flow — what proves the flow wrote
    // nothing is the pending secret it would otherwise have cleared.
    assertRowUntouched(seed, AccountInviteStatus.ACCEPTED, TwoFactorGateStatus.ACTIVE);
  }

  @Test
  void
      counsellorActivateTwoFactor_acceptorChangedDuringVerification_answers400AndLeavesGateClosed() {
    Seed seed = seedResumableInvite(AccountInviteTargetRole.COUNSELLOR);
    String otherAcceptorId = UUID.randomUUID().toString();
    concurrentlyDuringVerification(seed, row -> row.setAcceptedByUserId(otherAcceptorId));

    assertThatThrownBy(
            () -> counsellorOnboardingService.activateTwoFactor(seed.token(), ONE_TIME_PASSWORD))
        .isInstanceOf(BadRequestException.class);

    AccountInvite committed =
        assertRowUntouched(seed, AccountInviteStatus.ACCEPTED, TwoFactorGateStatus.PENDING_SETUP);
    // The gate must not be activated for the acceptor the code was never verified against either.
    assertThat(committed.getAcceptedByUserId()).isEqualTo(otherAcceptorId);
  }

  @Test
  void counsellorActivateTwoFactor_secretRotatedDuringVerification_answers400AndLeavesGateClosed() {
    Seed seed = seedResumableInvite(AccountInviteTargetRole.COUNSELLOR);
    concurrentlyDuringVerification(seed, row -> row.setTotpPendingSecret(ROTATED_SECRET));

    assertThatThrownBy(
            () -> counsellorOnboardingService.activateTwoFactor(seed.token(), ONE_TIME_PASSWORD))
        .isInstanceOf(BadRequestException.class);

    assertRowUntouched(
        seed, AccountInviteStatus.ACCEPTED, TwoFactorGateStatus.PENDING_SETUP, ROTATED_SECRET);
  }

  // --- tenant-admin onboarding ---

  @Test
  void tenantAdminActivateTwoFactor_revokedDuringVerification_answersRevokedAndLeavesGateClosed() {
    Seed seed = seedResumableInvite(AccountInviteTargetRole.TENANT_ADMIN);
    concurrentlyDuringVerification(seed, row -> row.setStatus(AccountInviteStatus.REVOKED));

    assertLinkDeath(
        () -> tenantAdminOnboardingService.activateTwoFactor(seed.token(), ONE_TIME_PASSWORD),
        AccountInviteLinkException.Reason.REVOKED);

    assertRowUntouched(seed, AccountInviteStatus.REVOKED, TwoFactorGateStatus.PENDING_SETUP);
  }

  @Test
  void tenantAdminActivateTwoFactor_expiredDuringVerification_answersConsumedAndLeavesGateClosed() {
    Seed seed = seedResumableInvite(AccountInviteTargetRole.TENANT_ADMIN);
    concurrentlyDuringVerification(
        seed, row -> row.setExpiresAt(LocalDateTime.now().minusMinutes(5)));

    assertLinkDeath(
        () -> tenantAdminOnboardingService.activateTwoFactor(seed.token(), ONE_TIME_PASSWORD),
        AccountInviteLinkException.Reason.CONSUMED);

    assertRowUntouched(seed, AccountInviteStatus.ACCEPTED, TwoFactorGateStatus.PENDING_SETUP);
  }

  @Test
  void
      tenantAdminActivateTwoFactor_gateActivatedDuringVerification_answersConsumedAndWritesNothing() {
    Seed seed = seedResumableInvite(AccountInviteTargetRole.TENANT_ADMIN);
    concurrentlyDuringVerification(seed, row -> row.setTwoFactorStatus(TwoFactorGateStatus.ACTIVE));

    assertLinkDeath(
        () -> tenantAdminOnboardingService.activateTwoFactor(seed.token(), ONE_TIME_PASSWORD),
        AccountInviteLinkException.Reason.CONSUMED);

    assertRowUntouched(seed, AccountInviteStatus.ACCEPTED, TwoFactorGateStatus.ACTIVE);
  }

  @Test
  void
      tenantAdminActivateTwoFactor_acceptorChangedDuringVerification_answers400AndLeavesGateClosed() {
    Seed seed = seedResumableInvite(AccountInviteTargetRole.TENANT_ADMIN);
    String otherAcceptorId = UUID.randomUUID().toString();
    concurrentlyDuringVerification(seed, row -> row.setAcceptedByUserId(otherAcceptorId));

    assertThatThrownBy(
            () -> tenantAdminOnboardingService.activateTwoFactor(seed.token(), ONE_TIME_PASSWORD))
        .isInstanceOf(BadRequestException.class);

    AccountInvite committed =
        assertRowUntouched(seed, AccountInviteStatus.ACCEPTED, TwoFactorGateStatus.PENDING_SETUP);
    assertThat(committed.getAcceptedByUserId()).isEqualTo(otherAcceptorId);
  }

  @Test
  void
      tenantAdminActivateTwoFactor_secretRotatedDuringVerification_answers400AndLeavesGateClosed() {
    Seed seed = seedResumableInvite(AccountInviteTargetRole.TENANT_ADMIN);
    concurrentlyDuringVerification(seed, row -> row.setTotpPendingSecret(ROTATED_SECRET));

    assertThatThrownBy(
            () -> tenantAdminOnboardingService.activateTwoFactor(seed.token(), ONE_TIME_PASSWORD))
        .isInstanceOf(BadRequestException.class);

    assertRowUntouched(
        seed, AccountInviteStatus.ACCEPTED, TwoFactorGateStatus.PENDING_SETUP, ROTATED_SECRET);
  }

  // --- harness ---

  /** A seeded invite that is resumable at the 2FA step, plus the raw token that opens it. */
  private record Seed(String token, Long inviteId, String acceptorId) {}

  /**
   * Stubs the Keycloak round trip of the 2FA step and lets {@code competingChange} commit INSIDE
   * it: the invite's lock is released for that round trip, so this is exactly the window between
   * the eligibility check and the gate activation.
   */
  private void concurrentlyDuringVerification(Seed seed, Consumer<AccountInvite> competingChange) {
    when(keycloakService.findById(seed.acceptorId()))
        .thenReturn(
            Optional.of(
                new IdentityProfile(
                    seed.acceptorId(), "enc.onboarding.user", "Lisa", "Simpson", "l@oriso.org")));
    when(keycloakService.setUpOtpCredential(
            anyString(), eq(ONE_TIME_PASSWORD), eq(VERIFIED_SECRET)))
        .thenAnswer(
            invocation -> {
              inSeparateTransaction(
                  () -> {
                    AccountInvite competitor = committedInviteWithin(seed.inviteId());
                    competingChange.accept(competitor);
                    competitor.setUpdateDate(LocalDateTime.now());
                    return accountInviteRepository.save(competitor);
                  });
              // Keycloak accepted the code — against the state that no longer exists.
              return true;
            });
  }

  private static void assertLinkDeath(
      Runnable activation, AccountInviteLinkException.Reason expectedReason) {
    assertThatThrownBy(activation::run)
        .isInstanceOf(AccountInviteLinkException.class)
        .extracting(exception -> ((AccountInviteLinkException) exception).getReason())
        .isEqualTo(expectedReason);
  }

  private AccountInvite assertRowUntouched(
      Seed seed, AccountInviteStatus expectedStatus, TwoFactorGateStatus expectedGateStatus) {
    return assertRowUntouched(seed, expectedStatus, expectedGateStatus, VERIFIED_SECRET);
  }

  /**
   * The gate activation writes exactly two things: it clears the pending secret and it flips the
   * gate to ACTIVE. Asserting both against the state the competing writer left proves the flow
   * wrote nothing at all.
   */
  private AccountInvite assertRowUntouched(
      Seed seed,
      AccountInviteStatus expectedStatus,
      TwoFactorGateStatus expectedGateStatus,
      String expectedPendingSecret) {
    AccountInvite committed = inSeparateTransaction(() -> committedInviteWithin(seed.inviteId()));
    assertThat(committed.getTotpPendingSecret()).isEqualTo(expectedPendingSecret);
    assertThat(committed.getTwoFactorStatus()).isEqualTo(expectedGateStatus);
    assertThat(committed.getStatus()).isEqualTo(expectedStatus);
    return committed;
  }

  private AccountInvite committedInviteWithin(Long inviteId) {
    return accountInviteRepository
        .findById(inviteId)
        .orElseThrow(() -> new AssertionError("The seeded invite disappeared"));
  }

  private <T> T inSeparateTransaction(Supplier<T> action) {
    TransactionTemplate separateTransaction = new TransactionTemplate(transactionManager);
    separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return separateTransaction.execute(status -> action.get());
  }

  /** An invite that already went through registration and waits at the mandatory 2FA step. */
  private Seed seedResumableInvite(AccountInviteTargetRole targetRole) {
    String rawToken = "two-factor-revalidation-" + UUID.randomUUID();
    // The identity columns hold a UUID (36 chars) — a longer marker would not fit.
    String acceptorId = UUID.randomUUID().toString();
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
                    .expiresAt(LocalDateTime.now().plusDays(1))
                    .status(AccountInviteStatus.ACCEPTED)
                    .acceptedAt(LocalDateTime.now())
                    .acceptedByUserId(acceptorId)
                    .emailVerificationStatus(EmailVerificationStatus.VERIFIED)
                    .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
                    .totpPendingSecret(VERIFIED_SECRET)
                    .createDate(LocalDateTime.now())
                    .build())
            .getId();
    seededInviteIds.add(inviteId);
    return new Seed(rawToken, inviteId, acceptorId);
  }
}
