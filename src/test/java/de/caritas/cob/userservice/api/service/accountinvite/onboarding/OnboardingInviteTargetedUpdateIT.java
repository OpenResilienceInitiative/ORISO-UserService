package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.RegisterCounsellorCommand;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Data-integrity guard for the onboarding writes that follow a remote call (#1008 review).
 *
 * <p>Both flows deliberately leave their transaction for every remote call, so the invite they
 * carry across such a call is a DETACHED snapshot. Saving that snapshot back would merge ALL of its
 * columns — {@link AccountInvite} has neither {@code @Version} nor {@code @DynamicUpdate} — and
 * would therefore silently revert whatever another writer changed in the meantime (lost update).
 * The writes must instead re-read the row under its PESSIMISTIC_WRITE lock and set only the fields
 * they own.
 *
 * <p>The tests below make the race deterministic: the concurrent update runs INSIDE the stubbed
 * Keycloak call, i.e. exactly between the read and the write, in its own committed transaction.
 * Each test then reads the committed row and asserts BOTH halves — the field the flow owns is
 * written AND the concurrent change to an unrelated field survived. Restoring the old {@code
 * save(detachedSnapshot)} makes these tests fail on the surviving-change assertion.
 *
 * <p>Runs against the real database and outside a rolled-back test transaction, because only
 * committed state can prove the property; the seeded rows are removed in {@link
 * #deleteSeededInvites()}.
 */
@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OnboardingInviteTargetedUpdateIT {

  private static final Long AGENCY_ID = 275L;
  private static final Long DEPARTMENT_TOPIC_ID = 2L;
  private static final String CONCURRENT_LAST_NAME = "Concurrently Renamed";

  @Autowired private CounsellorOnboardingService counsellorOnboardingService;

  @Autowired private AccountInviteRepository accountInviteRepository;

  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private ConsultantAdminFacade consultantAdminFacade;

  /**
   * The single {@code keycloakService} bean implements ALL identity ports, so the concrete bean is
   * mocked — mocking one port interface would replace the bean and break the others at startup.
   */
  @MockitoBean private KeycloakService keycloakService;

  @MockitoBean private AgencyService agencyService;

  private final List<Long> seededInviteIds = new ArrayList<>();

  @BeforeEach
  void stubUpstreams() {
    when(agencyService.getAgencyWithoutCaching(AGENCY_ID))
        .thenReturn(new AgencyDTO().id(AGENCY_ID).topicIds(List.of(DEPARTMENT_TOPIC_ID)));
    when(keycloakService.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("technical-access-token", 60, 60, "refresh"));
  }

  @AfterEach
  void deleteSeededInvites() {
    seededInviteIds.forEach(accountInviteRepository::deleteById);
    seededInviteIds.clear();
  }

  @Test
  void registerCounsellor_concurrentUpdateDuringTheKeycloakCall_survivesThePendingSecretWrite() {
    String token = "targeted-update-register-" + UUID.randomUUID();
    // The identity columns hold a UUID (36 chars) — a longer marker would not fit.
    String consultantId = UUID.randomUUID().toString();
    Long inviteId = seedInvite(token, invite -> invite);

    when(consultantAdminFacade.createNewConsultant(any(CreateConsultantDTO.class)))
        .thenReturn(
            new ConsultantAdminResponseDTO().embedded(new ConsultantDTO().id(consultantId)));
    // The rename happens while the flow waits for Keycloak — after the provisioning transaction
    // committed and before the pending secret is written.
    when(keycloakService.getOtpCredential(anyString()))
        .thenAnswer(
            invocation -> {
              renameConcurrently(inviteId);
              return new IdentityOtpCredential(false, "TARGETEDSECRET", "QR", IdentityOtpType.APP);
            });

    var result =
        counsellorOnboardingService.registerCounsellor(
            token,
            new RegisterCounsellorCommand(
                "codex_targeted_update",
                "Valid-Test-Password-2026!",
                null,
                null,
                null,
                "Lisa",
                "Lisa S. (Nord)",
                List.of(DEPARTMENT_TOPIC_ID)));

    assertThat(result.totpSecret()).isEqualTo("TARGETEDSECRET");
    AccountInvite committed = committedInvite(inviteId);
    assertThat(committed.getTotpPendingSecret()).isEqualTo("TARGETEDSECRET");
    // Merging the snapshot the provisioning returned would have written the pre-rename last name
    // back over the concurrent update.
    assertThat(committed.getLastName()).isEqualTo(CONCURRENT_LAST_NAME);
    assertThat(committed.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
  }

  @Test
  void activateTwoFactor_concurrentUpdateDuringTheKeycloakCall_survivesTheGateConsumption() {
    String token = "targeted-update-two-factor-" + UUID.randomUUID();
    String consultantId = UUID.randomUUID().toString();
    Long inviteId =
        seedInvite(
            token,
            invite ->
                invite
                    .status(AccountInviteStatus.ACCEPTED)
                    .acceptedAt(LocalDateTime.now())
                    .acceptedByUserId(consultantId)
                    .emailVerificationStatus(EmailVerificationStatus.VERIFIED)
                    .totpPendingSecret("PENDINGSECRET"));

    when(keycloakService.findById(consultantId))
        .thenReturn(
            Optional.of(
                new IdentityProfile(
                    consultantId, "codex_targeted_update", "Lisa", "Simpson", "l@oriso.org")));
    when(keycloakService.setUpOtpCredential(anyString(), eq("123456"), eq("PENDINGSECRET")))
        .thenAnswer(
            invocation -> {
              renameConcurrently(inviteId);
              return true;
            });

    counsellorOnboardingService.activateTwoFactor(token, "123456");

    AccountInvite committed = committedInvite(inviteId);
    assertThat(committed.getTotpPendingSecret()).isNull();
    assertThat(committed.getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.ACTIVE);
    assertThat(committed.getLastName()).isEqualTo(CONCURRENT_LAST_NAME);
  }

  /** The other writer: changes a field NO onboarding write owns, and commits before we return. */
  private void renameConcurrently(Long inviteId) {
    inSeparateTransaction(
        () -> {
          AccountInvite concurrent = committedInviteWithin(inviteId);
          concurrent.setLastName(CONCURRENT_LAST_NAME);
          concurrent.setUpdateDate(LocalDateTime.now());
          return accountInviteRepository.save(concurrent);
        });
  }

  private AccountInvite committedInvite(Long inviteId) {
    return inSeparateTransaction(() -> committedInviteWithin(inviteId));
  }

  private AccountInvite committedInviteWithin(Long inviteId) {
    return accountInviteRepository
        .findById(inviteId)
        .orElseThrow(() -> new AssertionError("The seeded invite disappeared"));
  }

  private <T> T inSeparateTransaction(java.util.function.Supplier<T> action) {
    TransactionTemplate separateTransaction = new TransactionTemplate(transactionManager);
    separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return separateTransaction.execute(status -> action.get());
  }

  private Long seedInvite(
      String rawToken,
      Function<AccountInvite.AccountInviteBuilder, AccountInvite.AccountInviteBuilder> customize) {
    AccountInvite.AccountInviteBuilder builder =
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .tenantId(79L)
            .recipientEmail("lisa.simpson@oriso.org")
            .firstName("Lisa")
            .lastName("Simpson")
            .agencyId(AGENCY_ID)
            .departmentId(DEPARTMENT_TOPIC_ID)
            .tokenHash(AccountInviteService.hash(rawToken))
            .expiresAt(LocalDateTime.now().plusDays(1))
            .status(AccountInviteStatus.EMAIL_SENT)
            .emailVerificationStatus(EmailVerificationStatus.PENDING)
            .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
            .createDate(LocalDateTime.now());
    Long inviteId = accountInviteRepository.save(customize.apply(builder).build()).getId();
    seededInviteIds.add(inviteId);
    return inviteId;
  }
}
