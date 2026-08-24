package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hardening for bug ORISO-Admin#569: the accept link is strictly single-use — two concurrent
 * accepts of the same token must never both claim the invite. The claim itself is a guarded UPDATE
 * ({@code ... WHERE status = EMAIL_SENT}), so the guarantee holds even if the database does not
 * honor the pessimistic lock hint on the token lookup.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AccountInviteService.class)
class AccountInviteAcceptRaceIT {

  private static final String RAW_TOKEN = "race-raw-token";

  @Autowired private AccountInviteService service;
  @Autowired private AccountInviteRepository accountInviteRepository;

  @MockitoBean private AuthenticatedUser authenticatedUser;
  @MockitoBean private IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private TenantIdAllocationClient tenantIdAllocationClient;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private InviteAcceptUrlBuilder inviteAcceptUrlBuilder;

  @MockitoBean
  private de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService
      inviteMailDispatchService;

  @MockitoBean private InviteEmailDeliveryFailureRecorder deliveryFailureRecorder;

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
  }

  @Test
  void concurrentAccepts_Should_LetExactlyOneClaimTheInvite_When_TwoFactorGateSatisfied()
      throws Exception {
    AccountInvite invite =
        persistedEmailSentInvite(
            AccountInviteTargetRole.PLATFORM_ADMIN, TwoFactorGateStatus.NOT_REQUIRED);

    List<Object> outcomes =
        runConcurrently(
            () -> service.acceptInvite(RAW_TOKEN, "user-a"),
            () -> service.acceptInvite(RAW_TOKEN, "user-b"));

    List<AccountInvite> successes =
        outcomes.stream()
            .filter(AccountInvite.class::isInstance)
            .map(AccountInvite.class::cast)
            .toList();
    List<AccountInviteLinkException> rejections =
        outcomes.stream()
            .filter(AccountInviteLinkException.class::isInstance)
            .map(AccountInviteLinkException.class::cast)
            .toList();

    // Exactly one caller wins the single-use claim; the loser gets the distinct CONSUMED error.
    assertThat(successes).hasSize(1);
    assertThat(rejections).hasSize(1);
    assertThat(rejections.get(0).getReason()).isEqualTo(AccountInviteLinkException.Reason.CONSUMED);

    AccountInvite persisted = accountInviteRepository.findById(invite.getId()).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    // The persisted claim belongs to the winner — no lost update, no mixed state.
    assertThat(persisted.getAcceptedByUserId())
        .isEqualTo(successes.get(0).getAcceptedByUserId())
        .isIn("user-a", "user-b");
    assertThat(persisted.getAcceptedAt()).isNotNull();
    assertThat(persisted.getEmailVerificationStatus()).isEqualTo(EmailVerificationStatus.VERIFIED);
  }

  @Test
  void concurrentAccepts_Should_PersistExactlyOneClaim_When_ResumeContractApplies()
      throws Exception {
    // Tenant-admin invites stay resumable while 2FA is pending (ORISO-Admin#569 resume
    // contract): the racer that loses the claim receives the winner's committed state as an
    // idempotent resume instead of an error — but the claim itself must still happen once.
    AccountInvite invite =
        persistedEmailSentInvite(
            AccountInviteTargetRole.TENANT_ADMIN, TwoFactorGateStatus.PENDING_SETUP);

    List<Object> outcomes =
        runConcurrently(
            () -> service.acceptInvite(RAW_TOKEN, "owner-a"),
            () -> service.acceptInvite(RAW_TOKEN, "owner-b"));

    List<AccountInvite> successes =
        outcomes.stream()
            .filter(AccountInvite.class::isInstance)
            .map(AccountInvite.class::cast)
            .toList();
    assertThat(successes).hasSize(2);

    AccountInvite persisted = accountInviteRepository.findById(invite.getId()).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    // No lost update: exactly one contender's claim is persisted, and the resumed response of
    // the loser mirrors that same committed state.
    assertThat(persisted.getAcceptedByUserId()).isIn("owner-a", "owner-b");
    assertThat(successes)
        .extracting(AccountInvite::getAcceptedByUserId)
        .containsOnly(persisted.getAcceptedByUserId());
    assertThat(persisted.getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.PENDING_SETUP);
  }

  private AccountInvite persistedEmailSentInvite(
      AccountInviteTargetRole targetRole, TwoFactorGateStatus twoFactorStatus) {
    LocalDateTime now = LocalDateTime.now();
    return accountInviteRepository.save(
        AccountInvite.builder()
            .targetRole(targetRole)
            .recipientEmail("race@example.org")
            .tokenHash(AccountInviteService.hash(RAW_TOKEN))
            .status(AccountInviteStatus.EMAIL_SENT)
            .emailVerificationStatus(EmailVerificationStatus.PENDING)
            .twoFactorStatus(twoFactorStatus)
            .expiresAt(now.plusDays(30))
            .createDate(now)
            .updateDate(now)
            .build());
  }

  /** Starts both calls on a shared latch and collects results and exceptions alike. */
  private List<Object> runConcurrently(
      Callable<AccountInvite> first, Callable<AccountInvite> second) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch startLatch = new CountDownLatch(1);
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (Callable<AccountInvite> call : List.of(first, second)) {
        futures.add(
            executor.submit(
                () -> {
                  startLatch.await(5, TimeUnit.SECONDS);
                  try {
                    return (Object) call.call();
                  } catch (Exception exception) {
                    return exception;
                  }
                }));
      }
      startLatch.countDown();
      List<Object> outcomes = new ArrayList<>();
      for (Future<Object> future : futures) {
        outcomes.add(future.get(30, TimeUnit.SECONDS));
      }
      return outcomes;
    } finally {
      executor.shutdownNow();
    }
  }
}
