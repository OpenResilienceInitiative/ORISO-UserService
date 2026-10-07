package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteProvisioningStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyFacts;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.AgencyCreationClient;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@WithTenant(1L)
class AgencyAdminOnboardingWizardIT
    extends de.caritas.cob.userservice.api.testHelper.AccountInactivityPolicyHttpFixture {

  private static final long TENANT = 79L;
  private static final long AGENCY = 275L;
  private static final long NEW_AGENCY = 276L;
  private static final long TOPIC = 2L;
  private static final String CONSULTANT_ID = "c0a1e5e5-1026-4a4a-9d1e-000000000001";
  private static final String ADMIN_ONLY_ID = "c0a1e5e5-1026-4a4a-9d1e-000000000002";
  private static final String RETRY_ADMIN_ID = "c0a1e5e5-1026-4a4a-9d1e-000000000003";
  private static final String CSRF = "it-csrf-token";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF);

  /** The public route resolves to the main tenant, as on the single-domain deployment. */
  @MockitoBean private TenantResolverService tenantResolverService;

  @MockitoBean private TenantService tenantService;

  @Autowired private MockMvc mockMvc;
  @MockitoSpyBean private AccountInviteRepository accountInviteRepository;
  @Autowired private AdminRepository adminRepository;
  @Autowired private AdminAgencyRepository adminAgencyRepository;

  @Autowired
  private de.caritas.cob.userservice.api.adapters.keycloak.commands
          .IdentityCreationFinalizationRetry
      creationFinalization;

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

  private final java.util.List<Long> seededInviteIds = new java.util.ArrayList<>();
  private final java.util.Deque<String> adminCreationIds = new java.util.ArrayDeque<>();
  @MockitoBean private AgencyService agencyService;
  @MockitoBean private AgencyCreationClient agencyCreationClient;

  /** The tenant's active topics — the wizard offers them on top of the agency's coverage. */
  @MockitoBean private TopicService topicService;

  /** The accept re-checks the agency with the service token (ORISO-Admin#1026 P2-3). */
  @MockitoBean private AgencyFacts agencyFacts;

  @BeforeEach
  void upstreams() {
    when(agencyFacts.find(anyLong()))
        .thenAnswer(
            invocation ->
                Optional.of(
                    new AgencyFacts.Agency(invocation.getArgument(0), TENANT, false, List.of())));
    when(agencyService.getAgencyWithoutCaching(AGENCY))
        .thenReturn(new AgencyDTO().id(AGENCY).tenantId(TENANT).topicIds(List.of(TOPIC)));
    when(agencyService.getAgencyWithoutCaching(NEW_AGENCY)).thenReturn(null);
    when(topicService.getAllActiveTopicsMap())
        .thenReturn(Map.of(TOPIC, new TopicDTO().id(TOPIC).name("Sucht")));
    when(tenantService.getRestrictedTenantDataFresh(anyLong()))
        .thenReturn(de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures.tenant());
    when(tenantService.getSingleTenancyTenantDataFresh())
        .thenReturn(de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures.tenant());
    adminCreationIds.clear();
    adminCreationIds.add(ADMIN_ONLY_ID);
    givenTaskAccounts(
        body ->
            "CONSULTANT_AGENCY_ADMIN".equals(body.get("registrationKind"))
                ? CONSULTANT_ID
                : adminCreationIds.size() > 1
                    ? adminCreationIds.removeFirst()
                    : adminCreationIds.getFirst(),
        "ADMINTOTPSECRET");
    when(agencyService.getPublicImportAgency(AGENCY, TENANT))
        .thenReturn(
            new AgencyDTO()
                .id(AGENCY)
                .tenantId(TENANT)
                .consultingType(1)
                .topicIds(List.of(TOPIC))
                .teamAgency(false));
    when(agencyService.getPublicImportAgency(NEW_AGENCY, TENANT))
        .thenReturn(
            new AgencyDTO()
                .id(NEW_AGENCY)
                .tenantId(TENANT)
                .consultingType(1)
                .topicIds(List.of(TOPIC))
                .teamAgency(false));
  }

  @AfterEach
  void cleanUp() {
    seededInviteIds.forEach(accountInviteRepository::deleteById);
    seededInviteIds.clear();
    cleanCreatedIdentities();
    Tenants.acrossAll(
        () -> {
          for (String id : List.of(CONSULTANT_ID, ADMIN_ONLY_ID, RETRY_ADMIN_ID)) {
            adminAgencyRepository.deleteAll(adminAgencyRepository.findByAdminId(id));
            adminRepository.findById(id).ifPresent(adminRepository::delete);
          }
        });
    // The admins live in the invite's Träger, so only a read across all of them proves they are
    // gone.
    Tenants.acrossAll(
        () -> {
          for (String id : List.of(CONSULTANT_ID, ADMIN_ONLY_ID, RETRY_ADMIN_ID)) {
            assertThat(adminRepository.findById(id)).isEmpty();
            assertThat(adminAgencyRepository.findByAdminId(id)).isEmpty();
          }
        });
  }

  @Test
  void resolve_Should_ReportTheAgencyAdminRoleAndTheProposedAlsoCounsellorFlag() throws Exception {
    String token = seedAgencyAdminInvite(AGENCY, false);

    mockMvc
        .perform(get("/users/account-invites/{token}/onboarding", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetRole").value("AGENCY_ADMIN"))
        .andExpect(jsonPath("$.alsoCounsellor").value(false))
        .andExpect(jsonPath("$.agencyId").value(AGENCY))
        .andExpect(jsonPath("$.agencyExists").value(true))
        .andExpect(jsonPath("$.topics[0].id").value(TOPIC));
  }

  @Test
  void register_Should_CreateConsultantAndAgencyAdmin_When_AlsoCounsellorIsOn() throws Exception {
    String token = seedAgencyAdminInvite(AGENCY, true);

    register(token, "admin_counsellor", null, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consultantId").value(CONSULTANT_ID))
        .andExpect(jsonPath("$.phase").value("PENDING_2FA_ACTIVATION"));

    assertThat(Tenants.in(TENANT, () -> consultantRepository.findById(CONSULTANT_ID))).isPresent();
    // Read as the invite's Träger: the new admin must have landed in it.
    Admin admin = Tenants.in(TENANT, () -> adminRepository.findById(CONSULTANT_ID).orElseThrow());
    assertThat(admin.getType()).isEqualTo(Admin.AdminType.AGENCY);
    assertThat(adminAgencyRepository.findByAdminIdAndAgencyId(CONSULTANT_ID, AGENCY)).hasSize(1);
    assertThat(accountCreates())
        .singleElement()
        .satisfies(
            command ->
                assertThat((java.util.List<String>) command.body().get("roles"))
                    .contains("consultant", "restricted-agency-admin"));
  }

  @Test
  void register_Should_CreateOnlyTheAgencyAdmin_When_TheInviteeSwitchesAlsoCounsellorOff()
      throws Exception {
    String token = seedAgencyAdminInvite(AGENCY, true);

    register(token, "admin_only", false, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").value("PENDING_2FA_ACTIVATION"))
        .andExpect(jsonPath("$.twoFactor.secret").value("ADMINTOTPSECRET"));

    assertThat(Tenants.acrossAll(() -> consultantRepository.findById(CONSULTANT_ID))).isEmpty();
    // Read as the invite's Träger: the new admin must have landed in it.
    Admin admin = Tenants.in(TENANT, () -> adminRepository.findById(ADMIN_ONLY_ID).orElseThrow());
    assertThat(admin.getType()).isEqualTo(Admin.AdminType.AGENCY);
    assertThat(admin.getTenantId()).isEqualTo(TENANT);
    assertThat(adminAgencyRepository.findByAdminIdAndAgencyId(ADMIN_ONLY_ID, AGENCY)).hasSize(1);
    AccountInvite invite = seededInvite();
    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(invite.getProvisionedUserId()).isEqualTo(ADMIN_ONLY_ID);
    assertThat(invite.getAlsoCounsellor()).isFalse();

    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/two-factor", token)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"123456\"}"))
        .andExpect(status().isOk());
    assertThat(seededInvite().getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.ACTIVE);
  }

  @Test
  void register_Should_LeaveNoAdminRows_When_AStepAfterTheAdminCreationFails() throws Exception {
    String token = seedAgencyAdminInvite(AGENCY, false);
    failTheAcceptStepFor(ADMIN_ONLY_ID);

    register(token, "admin_only", false, null).andExpect(status().isGone());

    Tenants.acrossAll(
        () -> {
          assertThat(adminRepository.findById(ADMIN_ONLY_ID)).isEmpty();
          assertThat(adminAgencyRepository.findByAdminId(ADMIN_ONLY_ID)).isEmpty();
        });
    AccountInvite invite = seededInvite();
    assertThat(invite.getProvisioningStatus()).isEqualTo(AccountInviteProvisioningStatus.FAILED);
    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  @Test
  void register_Should_Succeed_When_TheInviteeRetriesAfterAFailureAfterTheAdminCreation()
      throws Exception {
    String token = seedAgencyAdminInvite(AGENCY, false);
    failTheAcceptStepFor(ADMIN_ONLY_ID);
    // Keycloak hands out a new id on the retry; the admin row keeps the same username and email.
    adminCreationIds.add(RETRY_ADMIN_ID);
    register(token, "admin_only", false, null).andExpect(status().isGone());
    assertThat(nativeAccounts.commands())
        .noneMatch(
            command ->
                command.operation().equals("account.commit")
                    && ADMIN_ONLY_ID.equals(command.body().get("accountId")));
    assertThat(nativeAccounts.projections().get(ADMIN_ONLY_ID).enabled()).isFalse();

    // The real durable recovery finishes this failed creation before another claim can win.
    creationFinalization.retry();
    assertThat(nativeAccounts.commands())
        .anySatisfy(
            command -> {
              assertThat(command.operation()).isEqualTo("account.compensate");
              assertThat(command.body().get("accountId")).isEqualTo(ADMIN_ONLY_ID);
            });
    assertThat(Tenants.acrossAll(() -> adminRepository.findById(ADMIN_ONLY_ID))).isEmpty();

    register(token, "admin_only", false, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").value("PENDING_2FA_ACTIVATION"));

    Admin admin = Tenants.in(TENANT, () -> adminRepository.findById(RETRY_ADMIN_ID).orElseThrow());
    assertThat(admin.getType()).isEqualTo(Admin.AdminType.AGENCY);
    assertThat(adminAgencyRepository.findByAdminIdAndAgencyId(RETRY_ADMIN_ID, AGENCY)).hasSize(1);
    AccountInvite invite = seededInvite();
    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(invite.getProvisionedUserId()).isEqualTo(RETRY_ADMIN_ID);
  }

  @Test
  void register_Should_CreateTheNewAgencyFirst_When_TheAgencyIdIsStillAReservation()
      throws Exception {
    String token = seedAgencyAdminInvite(NEW_AGENCY, false);

    register(token, "admin_new_agency", null, "Beratungsstelle Nord").andExpect(status().isOk());

    verify(agencyCreationClient)
        .createAgencyWithReservedId(
            eq(NEW_AGENCY),
            eq("Beratungsstelle Nord"),
            eq(TENANT),
            any(),
            org.mockito.ArgumentMatchers.eq("owner-proof"));
    assertThat(adminAgencyRepository.findByAdminIdAndAgencyId(ADMIN_ONLY_ID, NEW_AGENCY))
        .hasSize(1);
  }

  @Test
  void onboarding_Should_OfferTheAgencyAdminEveryTopic_When_TheStoredPermissionIsNarrower()
      throws Exception {
    // Also when the stored row says NONE, e.g. from a partial deployment's column default.
    long otherTopic = 3L;
    when(topicService.getAllActiveTopicsMap())
        .thenReturn(
            Map.of(
                TOPIC,
                new TopicDTO().id(TOPIC).name("Sucht"),
                otherTopic,
                new TopicDTO().id(otherTopic).name("Schulden")));
    String token = seedAgencyAdminInvite(AGENCY, true);
    AccountInvite stored = seededInvite();
    stored.setTopicPermission(TopicPermission.NONE);
    accountInviteRepository.save(stored);

    mockMvc
        .perform(get("/users/account-invites/{token}/onboarding", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.topicPermission").value("CREATE"))
        .andExpect(jsonPath("$.availableTopics[?(@.id == 3)]").exists());

    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/register", token)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{ \"account\": { \"username\": \"admin_two_topics\", \"password\":"
                        + " \"Valid-Test-Password-2026!\" }, \"topicIds\": ["
                        + TOPIC
                        + ", "
                        + otherTopic
                        + "], \"alsoCounsellor\": true }"))
        .andExpect(status().isOk());
  }

  /** Counsellors queued for the new agency need at least one topic to pick from. */
  @Test
  void register_Should_Answer400AndCreateNothing_When_AFoundingAdminWhoDoesNotCounselSendsNoTopic()
      throws Exception {
    String token = seedAgencyAdminInvite(NEW_AGENCY, false);

    register(token, "admin_no_topic", false, "Beratungsstelle Nord", "")
        .andExpect(status().isBadRequest());

    verify(agencyCreationClient, never())
        .createAgencyWithReservedId(
            any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq("owner-proof"));
    assertThat(accountCreates()).isEmpty();
    assertThat(seededInvite().getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  @Test
  void register_Should_PassTheTopicToTheNewAgency_When_AFoundingAdminDoesNotCounsel()
      throws Exception {
    String token = seedAgencyAdminInvite(NEW_AGENCY, false);

    register(token, "admin_only", false, "Beratungsstelle Nord").andExpect(status().isOk());

    verify(agencyCreationClient)
        .createAgencyWithReservedId(
            eq(NEW_AGENCY),
            eq("Beratungsstelle Nord"),
            eq(TENANT),
            eq(List.of(TOPIC)),
            org.mockito.ArgumentMatchers.eq("owner-proof"));
    assertThat(Tenants.acrossAll(() -> consultantRepository.findById(CONSULTANT_ID))).isEmpty();
  }

  /** ORISO-Admin#1026 P2-3: the agency may be soft-deleted between invite and accept. */
  @Test
  void register_Should_CreateNothing_When_TheExistingAgencyWasDeletedSinceTheInvite()
      throws Exception {
    agencyAsSeenByServiceToken(new AgencyFacts.Agency(AGENCY, TENANT, true, List.of(TOPIC)));
    String token = seedAgencyAdminInvite(AGENCY, false);

    register(token, "admin_only", false, null).andExpect(status().isNotFound());

    assertNoAgencyAdminCreated();
  }

  @Test
  void register_Should_CreateNothing_When_TheExistingAgencyIsGone() throws Exception {
    when(agencyFacts.find(AGENCY)).thenReturn(Optional.empty());
    String token = seedAgencyAdminInvite(AGENCY, false);

    register(token, "admin_only", false, null).andExpect(status().isNotFound());

    assertNoAgencyAdminCreated();
  }

  @Test
  void register_Should_CreateNothing_When_TheAgencyBelongsToAnotherTenant() throws Exception {
    // The service token sees every tenant, unlike the inviting admin's token.
    agencyAsSeenByServiceToken(new AgencyFacts.Agency(AGENCY, TENANT + 1, false, List.of(TOPIC)));
    String token = seedAgencyAdminInvite(AGENCY, false);

    register(token, "admin_only", false, null).andExpect(status().isNotFound());

    assertNoAgencyAdminCreated();
  }

  /** The invitee is anonymous, so the agency is only readable with the service token. */
  private void agencyAsSeenByServiceToken(AgencyFacts.Agency agency) {
    when(agencyFacts.find(AGENCY))
        .thenAnswer(
            invocation -> {
              assertThat(TechnicalAccessTokenContext.get())
                  .contains(
                      de.caritas
                          .cob
                          .userservice
                          .api
                          .testHelper
                          .BoundedIdentityHttpFixtures
                          .taskJwt(
                              de.caritas.cob.userservice.api.config.auth.TaskIdentity.CONFIG_WIZARD,
                              taskIdentities)
                          .getTokenValue());
              return Optional.of(agency);
            });
  }

  private void assertNoAgencyAdminCreated() {
    assertThat(accountCreates()).isEmpty();
    assertThat(adminAgencyRepository.findByAdminId(ADMIN_ONLY_ID)).isEmpty();
    AccountInvite invite = seededInvite();
    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(invite.getProvisionedUserId()).isNull();
  }

  private org.springframework.test.web.servlet.ResultActions register(
      String token, String username, Boolean alsoCounsellor, String agencyName) throws Exception {
    return register(token, username, alsoCounsellor, agencyName, String.valueOf(TOPIC));
  }

  private org.springframework.test.web.servlet.ResultActions register(
      String token, String username, Boolean alsoCounsellor, String agencyName, String topicIds)
      throws Exception {
    String alsoCounsellorJson =
        alsoCounsellor == null ? "" : ", \"alsoCounsellor\": " + alsoCounsellor;
    String agencyJson =
        agencyName == null ? "" : ", \"agency\": { \"name\": \"" + agencyName + "\" }";
    return mockMvc.perform(
        post("/users/account-invites/{token}/onboarding/register", token)
            .header("X-CSRF-Token", CSRF)
            .cookie(CSRF_COOKIE)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{ \"account\": { \"username\": \""
                    + username
                    + "\", \"password\": \"Valid-Test-Password-2026!\" }, \"topicIds\": ["
                    + topicIds
                    + "]"
                    + alsoCounsellorJson
                    + agencyJson
                    + " }"));
  }

  /**
   * An admin revokes the invite while it is being accepted. The link exception does not roll the
   * transaction back, so the admin rows already written would be committed.
   */
  private void failTheAcceptStepFor(String adminId) {
    doThrow(new AccountInviteLinkException(AccountInviteLinkException.Reason.REVOKED))
        .when(accountInviteRepository)
        .claimForAcceptance(
            org.mockito.ArgumentMatchers.anyLong(), eq(adminId), any(LocalDateTime.class));
  }

  private AccountInvite seededInvite() {
    return accountInviteRepository
        .findById(seededInviteIds.get(seededInviteIds.size() - 1))
        .orElseThrow();
  }

  private String seedAgencyAdminInvite(long agencyId, boolean alsoCounsellor) {
    String token = "agency-admin-wizard-" + UUID.randomUUID();
    var seeded =
        accountInviteRepository.save(
            AccountInvite.builder()
                .targetRole(AccountInviteTargetRole.AGENCY_ADMIN)
                .tenantId(TENANT)
                .agencyId(agencyId)
                .agencyReservationToken(agencyId == AGENCY ? null : "owner-proof")
                .agencyIdAllocationMode(
                    agencyId == AGENCY ? IdAllocationMode.EXISTING : IdAllocationMode.MANUAL)
                .departmentId(agencyId == AGENCY ? TOPIC : null)
                .alsoCounsellor(alsoCounsellor)
                .recipientEmail("agency-admin-" + UUID.randomUUID() + "@example.org")
                .firstName("Ada")
                .lastName("Lovelace")
                .tokenHash(AccountInviteService.hash(token))
                .expiresAt(LocalDateTime.now().plusDays(1))
                .status(AccountInviteStatus.EMAIL_SENT)
                .emailVerificationStatus(EmailVerificationStatus.PENDING)
                .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
                .createDate(LocalDateTime.now())
                .build());
    seededInviteIds.add(seeded.getId());
    return token;
  }
}
