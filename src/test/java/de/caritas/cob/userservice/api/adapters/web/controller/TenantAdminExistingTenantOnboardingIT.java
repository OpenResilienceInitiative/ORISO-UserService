package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.OperatorDpaContentClient;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.PublicDpaForwardClient;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantCreationClient;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Accepting a Träger-admin invite into an EXISTING Träger through the public endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@WithTenant(1L)
class TenantAdminExistingTenantOnboardingIT
    extends de.caritas.cob.userservice.api.testHelper.AccountInactivityPolicyHttpFixture {

  private static final long EXISTING_TENANT = 42L;
  private static final String NEW_ADMIN_ID = "b7f0f1a0-1026-4c4a-9d1e-000000000042";
  private static final String CSRF = "it-csrf-token";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF);

  // Only this class's invites go; seeded rows other classes read stay.
  private final java.util.List<Long> seededInviteIds = new java.util.ArrayList<>();

  /** The public route resolves to the main tenant, as on the single-domain deployment. */
  @MockitoBean private TenantResolverService tenantResolverService;

  @MockitoBean private TenantService tenantService;

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private AdminRepository adminRepository;

  @MockitoBean(name = "keycloakRestTemplate")
  private org.springframework.web.client.RestTemplate boundedIdentityHttp;

  @MockitoBean(name = "restTemplate")
  private org.springframework.web.client.RestTemplate taskAuthHttp;

  @MockitoBean private org.springframework.security.oauth2.jwt.JwtDecoder taskDecoder;

  @MockitoBean
  private de.caritas.cob.userservice.api.adapters.keycloak.commands.TaskIdentityGrant taskGrants;

  @Autowired
  private de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration taskIdentities;

  @Autowired private org.springframework.core.env.Environment identityEnvironment;
  @Autowired private com.fasterxml.jackson.databind.ObjectMapper identityMapper;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate fixtureJdbc;
  @Autowired private org.springframework.transaction.PlatformTransactionManager fixtureTransactions;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.ConsultantRepository consultantRepository;

  private de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures.Provider
      nativeAccounts;
  private final java.util.Set<String> createdIdentityIds = new java.util.LinkedHashSet<>();

  private void givenTaskAccounts(
      java.util.function.Function<java.util.Map<String, Object>, String> ids, String secret) {
    createdIdentityIds.clear();
    nativeAccounts =
        de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures.givenProvider(
            boundedIdentityHttp,
            taskGrants,
            taskIdentities,
            identityEnvironment,
            identityMapper,
            ids,
            createdIdentityIds::add);
    de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures.givenTaskGrants(
        taskAuthHttp, taskDecoder, taskIdentities);
    de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures.givenWizardPolicy(
        taskAuthHttp, taskIdentities, identityEnvironment, identityMapper);
    de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures.givenOtp(
        boundedIdentityHttp,
        taskIdentities,
        new de.caritas.cob.userservice.api.model.OtpInfoDTO()
            .otpSetup(false)
            .otpSecret(secret)
            .otpSecretQrCode("QRBASE64")
            .otpType(de.caritas.cob.userservice.api.model.OtpType.APP));
    de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures.givenConsultingPolicy(
        taskAuthHttp,
        new de.caritas.cob.userservice.consultingtypeservice.generated.web.model
                .ExtendedConsultingTypeResponseDTO()
            .id(1)
            .consultantBoundedToConsultingType(false));
  }

  private void cleanCreatedIdentities() {
    for (String id : createdIdentityIds) {
      de.caritas.cob.userservice.api.testHelper.ExistingAccountSetupFixtureCleanup.consultant(
          fixtureJdbc, fixtureTransactions, id);
      de.caritas.cob.userservice.api.testHelper.ExistingAccountSetupFixtureCleanup.admin(
          fixtureJdbc, fixtureTransactions, id);
      new org.springframework.transaction.support.TransactionTemplate(fixtureTransactions)
          .executeWithoutResult(
              status -> {
                fixtureJdbc.update(
                    "DELETE FROM identity_creation_attempt WHERE account_id = ?", id);
                fixtureJdbc.update("DELETE FROM account_inactivity WHERE identity_id = ?", id);
              });
    }
    createdIdentityIds.clear();
  }

  private java.util.List<
          de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures.Command>
      accountCreates() {
    return nativeAccounts.commands().stream()
        .filter(command -> command.operation().equals("account.create"))
        .toList();
  }

  @MockitoBean private TenantCreationClient tenantCreationClient;
  @MockitoBean private OperatorDpaContentClient operatorDpaContentClient;
  @MockitoBean private PublicDpaForwardClient publicDpaForwardClient;

  private String email;

  @BeforeEach
  void identityProvider() {
    email = "joins-" + UUID.randomUUID() + "@example.org";
    givenTaskAccounts(body -> NEW_ADMIN_ID, "JOINTOTPSECRET");
  }

  @AfterEach
  void cleanUp() {
    Tenants.acrossAll(() -> seededInviteIds.forEach(accountInviteRepository::deleteById));
    Tenants.acrossAll(
        () -> adminRepository.findById(NEW_ADMIN_ID).ifPresent(adminRepository::delete));
    cleanCreatedIdentities();
    // The admin lives in the existing Träger, so only a read across all of them proves it is gone.
    assertThat(Tenants.acrossAll(() -> adminRepository.findById(NEW_ADMIN_ID))).isEmpty();
  }

  @Test
  void resolve_Should_TellTheWizardThatTheInviteJoinsAnExistingTenant() throws Exception {
    String token = seedExistingTenantInvite();

    mockMvc
        .perform(get("/users/account-invites/{token}/onboarding", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetRole").value("TENANT_ADMIN"))
        .andExpect(jsonPath("$.joinsExistingTenant").value(true))
        .andExpect(jsonPath("$.tenantId").value(EXISTING_TENANT))
        .andExpect(jsonPath("$.reservedTenantId").doesNotExist())
        .andExpect(jsonPath("$.tenantIdReservationToken").doesNotExist());

    verifyNoInteractions(operatorDpaContentClient);
  }

  @Test
  void register_Should_AttachTheAdminToTheExistingTenant_WithoutCreatingOne() throws Exception {
    String token = seedExistingTenantInvite();

    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/register", token)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "account": { "password": "Valid-Test-Password-2026!" } }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(EXISTING_TENANT))
        .andExpect(jsonPath("$.twoFactor.secret").value("JOINTOTPSECRET"));

    verify(tenantCreationClient, never()).createTenant(any());
    verifyNoInteractions(operatorDpaContentClient);
    // Read as the existing Träger: the new admin must have landed in it.
    Admin admin =
        Tenants.in(EXISTING_TENANT, () -> adminRepository.findById(NEW_ADMIN_ID).orElseThrow());
    assertThat(admin.getType()).isEqualTo(Admin.AdminType.TENANT);
    assertThat(admin.getTenantId()).isEqualTo(EXISTING_TENANT);
    assertThat(accountCreates())
        .singleElement()
        .satisfies(
            command -> {
              assertThat(command.body().get("password")).isEqualTo("Valid-Test-Password-2026!");
              assertThat((java.util.List<String>) command.body().get("roles"))
                  .containsExactlyInAnyOrder(
                      "user-admin", "agency-admin", "tenant-admin", "topic-admin");
            });
    AccountInvite invite = seededInvite();
    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(invite.getAcceptedByUserId()).isEqualTo(NEW_ADMIN_ID);
    assertThat(invite.getDpaSignedAt()).isNull();
  }

  @Test
  void acceptedInviteAppSetupUsesItsBoundedTargetAndRejectsWrongCode() throws Exception {
    String token = seedExistingTenantInvite();
    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/register", token)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":{\"password\":\"Valid-Test-Password-2026!\"}}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/two-factor", token)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"000000\",\"method\":\"APP\"}"))
        .andExpect(status().isBadRequest());
    assertThat(seededInvite().getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.PENDING_SETUP);
    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/two-factor", token)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"123456\",\"method\":\"APP\"}"))
        .andExpect(status().isOk());
    assertThat(seededInvite().getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.ACTIVE);
    assertThat(seededInvite().getAcceptedByUserId()).isEqualTo(NEW_ADMIN_ID);
    assertThat(seededInvite().getTenantId()).isEqualTo(EXISTING_TENANT);
    de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures
        .assertAcceptedInvitationReads(boundedIdentityHttp, seededInvite());
  }

  @Test
  void forwardDpa_Should_Refuse400_When_TheTenantAlreadyExists() throws Exception {
    String token = seedExistingTenantInvite();

    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/dpa-forward", token)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        // The existing-tenant guard's reason, so a 400 from binding or validation cannot pass.
        .andExpect(jsonPath("$.message", containsString("joins an existing tenant")));

    verifyNoInteractions(publicDpaForwardClient, operatorDpaContentClient);
    AccountInvite invite = seededInvite();
    assertThat(invite.getDpaForwardCount()).isZero();
    assertThat(invite.getDpaForwardedAt()).isNull();
    assertThat(invite.getDpaSignedAt()).isNull();
    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  /**
   * The invite this test seeded, by id: other classes leave rows in the shared table, and an
   * accepted invite may no longer carry its token hash. findByTokenHash would also need a lock.
   */
  private AccountInvite seededInvite() {
    Long id = seededInviteIds.get(seededInviteIds.size() - 1);
    return accountInviteRepository.findAll().stream()
        .filter(invite -> id.equals(invite.getId()))
        .findFirst()
        .orElseThrow();
  }

  private String seedExistingTenantInvite() {
    String token = "join-existing-tenant-" + UUID.randomUUID();
    var invite =
        accountInviteRepository.save(
            AccountInvite.builder()
                .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
                .tenantId(EXISTING_TENANT)
                .tenantIdAllocationMode(IdAllocationMode.EXISTING)
                .recipientEmail(email)
                .firstName("Grace")
                .lastName("Hopper")
                .tokenHash(AccountInviteService.hash(token))
                .expiresAt(LocalDateTime.now().plusDays(1))
                .status(AccountInviteStatus.EMAIL_SENT)
                .emailVerificationStatus(EmailVerificationStatus.PENDING)
                .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
                .createDate(LocalDateTime.now())
                .build());
    seededInviteIds.add(invite.getId());
    return token;
  }
}
