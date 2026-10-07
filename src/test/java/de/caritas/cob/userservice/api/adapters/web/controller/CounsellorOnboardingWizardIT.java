package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyFacts;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end wiring of the public counsellor onboarding wizard endpoints (#997) through the real
 * HTTP layer: resolve → register → resume → two-factor, plus the link-death answers. The consultant
 * creation uses the real creation saga, bounded signed account commands and persisted relations.
 * Native identity HTTP and token decoding are the external test seams.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@WithTenant(1L)
class CounsellorOnboardingWizardIT
    extends de.caritas.cob.userservice.api.testHelper.AccountInactivityPolicyHttpFixture {

  private final java.util.List<Long> seededInviteIds = new java.util.ArrayList<>();

  private static final String CONSULTANT_ID = "c0a1e5e5-1026-4a4a-9d1e-000000000079";

  private static final Long AGENCY_ID = 275L;

  /** The invite's routed department topic — always part of the coverage, agency up or down. */
  private static final Long DEPARTMENT_TOPIC_ID = 2L;

  /**
   * The public onboarding POSTs are protected by the stateless double-submit CSRF filter (the SPA
   * sends a self-issued cookie/header pair); the IT does the same instead of poking holes into the
   * production CSRF configuration.
   */
  private static final String CSRF = "it-csrf-token";

  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF);

  /** The public route resolves to the main tenant, as on the single-domain deployment. */
  @MockitoBean private TenantResolverService tenantResolverService;

  @MockitoBean private TenantService tenantService;

  @Autowired private MockMvc mockMvc;

  @Autowired private AccountInviteRepository accountInviteRepository;

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

  /**
   * The invite's topic coverage comes from AgencyService (#1008 review). Without a stub the
   * unreachable upstream of the {@code testing} profile would silently decide the coverage — and
   * with it the HTTP answers — so every test here states the upstream behaviour it relies on:
   * {@link #agencyIsHealthy()} for the documented contract, {@link #agencyIsDown()} for the outage.
   */
  @MockitoBean private AgencyService agencyService;

  /** The accept re-checks the agency with the service token (ORISO-Admin#1026 P2-3). */
  @MockitoBean private AgencyFacts agencyFacts;

  @BeforeEach
  void configureProvisioning() {
    when(agencyFacts.find(anyLong()))
        .thenAnswer(
            invocation ->
                Optional.of(
                    new AgencyFacts.Agency(invocation.getArgument(0), 79L, false, List.of())));
    agencyIsHealthy();
    when(tenantService.getRestrictedTenantDataFresh(anyLong()))
        .thenReturn(de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures.tenant());
    when(tenantService.getSingleTenancyTenantDataFresh())
        .thenReturn(de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures.tenant());
    givenTaskAccounts(body -> CONSULTANT_ID, "WIZARDTOTPSECRET");
    when(agencyService.getPublicImportAgency(AGENCY_ID, 79L))
        .thenReturn(
            new AgencyDTO()
                .id(AGENCY_ID)
                .tenantId(79L)
                .consultingType(1)
                .topicIds(List.of(DEPARTMENT_TOPIC_ID, 7L))
                .teamAgency(false));
  }

  /** AgencyService answers: the invite's agency covers the topics 2 (department) and 7. */
  private void agencyIsHealthy() {
    when(agencyService.getAgencyWithoutCaching(AGENCY_ID))
        .thenReturn(
            new AgencyDTO().id(AGENCY_ID).tenantId(79L).topicIds(List.of(DEPARTMENT_TOPIC_ID, 7L)));
  }

  /** AgencyService is unreachable — the coverage degrades to the invite's department topic. */
  private void agencyIsDown() {
    when(agencyService.getAgencyWithoutCaching(AGENCY_ID))
        .thenThrow(new IllegalStateException("agency service unreachable"));
  }

  private void seedInvite(String rawToken) throws Exception {
    var seeded =
        accountInviteRepository.save(
            AccountInvite.builder()
                .targetRole(AccountInviteTargetRole.COUNSELLOR)
                .tenantId(79L)
                .recipientEmail("lisa.simpson@example.org")
                .firstName("Lisa")
                .lastName("Simpson")
                .agencyId(AGENCY_ID)
                .departmentId(DEPARTMENT_TOPIC_ID)
                .tokenHash(sha256(rawToken))
                .expiresAt(LocalDateTime.now().plusDays(1))
                .status(AccountInviteStatus.EMAIL_SENT)
                .emailVerificationStatus(EmailVerificationStatus.PENDING)
                .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
                .createDate(LocalDateTime.now())
                .build());
    seededInviteIds.add(seeded.getId());
  }

  @org.junit.jupiter.api.AfterEach
  void cleanUpCreatedAccounts() {
    seededInviteIds.forEach(accountInviteRepository::deleteById);
    seededInviteIds.clear();
    cleanCreatedIdentities();
  }

  @Test
  void wizardFlow_resolveRegisterResumeTwoFactor_runsOnTheSameCreationPathAsTheAdminForm()
      throws Exception {
    String token = "emailed-counsellor-wizard-token-" + java.util.UUID.randomUUID();
    seedInvite(token);

    // 1) Resolve: prefill data + the invite's topic coverage as AgencyService reports it
    // (department topic 2 plus the agency's topic 7 — see agencyIsHealthy()).
    mockMvc
        .perform(get("/users/account-invites/{token}/onboarding", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetRole").value("COUNSELLOR"))
        .andExpect(jsonPath("$.recipientEmail").value("lisa.simpson@example.org"))
        .andExpect(jsonPath("$.firstName").value("Lisa"))
        .andExpect(jsonPath("$.lastName").value("Simpson"))
        .andExpect(jsonPath("$.tenantId").value(79))
        .andExpect(jsonPath("$.agencyId").value(275))
        .andExpect(jsonPath("$.departmentId").value(2))
        .andExpect(jsonPath("$.topics[0].id").value(2))
        .andExpect(jsonPath("$.topics[1].id").value(7))
        .andExpect(jsonPath("$.topics.length()").value(2))
        .andExpect(jsonPath("$.phase").doesNotExist());

    // 2) Register with the wizard fields.
    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/register", token)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "account": { "username": "codex_wizard_counsellor", "password": "Valid-Test-Password-2026!" },
                      "person": { "salutation": "counsellor_female", "position": "Head of centre", "title": "Dipl.-Soz.Päd." },
                      "names": { "publicName": "Lisa", "internalDisplayName": "Lisa S. (Nord)" },
                      "topicIds": [2]
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consultantId").value(CONSULTANT_ID))
        .andExpect(jsonPath("$.phase").value("PENDING_2FA_ACTIVATION"))
        .andExpect(jsonPath("$.twoFactor.secret").value("WIZARDTOTPSECRET"))
        .andExpect(jsonPath("$.twoFactor.qrCodeBase64").value("QRBASE64"));

    // Real local creation preserves the wizard's submitted profile and agency scope.
    new org.springframework.transaction.support.TransactionTemplate(fixtureTransactions)
        .executeWithoutResult(
            status -> {
              var consultant =
                  de.caritas.cob.userservice.api.tenant.Tenants.in(
                      79L, () -> consultantRepository.findById(CONSULTANT_ID).orElseThrow());
              assertThat(
                      new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
                          .decodeUsername(consultant.getUsername()))
                  .isEqualTo("codex_wizard_counsellor");
              assertThat(consultant.getEmail()).isEqualTo("lisa.simpson@example.org");
              assertThat(consultant.getTenantId()).isEqualTo(79L);
              assertThat(consultant.getConsultantTopics())
                  .extracting(de.caritas.cob.userservice.api.model.ConsultantTopic::getTopicId)
                  .containsExactly(2L);
              assertThat(consultant.getSalutation()).isEqualTo("counsellor_female");
              assertThat(consultant.getPosition()).isEqualTo("Head of centre");
              assertThat(consultant.getTitle()).isEqualTo("Dipl.-Soz.Päd.");
              assertThat(consultant.getDisplayName()).isEqualTo("Lisa");
              assertThat(consultant.getInternalDisplayName()).isEqualTo("Lisa S. (Nord)");
              assertThat(consultant.getConsultantAgencies())
                  .extracting(de.caritas.cob.userservice.api.model.ConsultantAgency::getAgencyId)
                  .containsExactly(AGENCY_ID);
            });
    assertThat(accountCreates())
        .singleElement()
        .satisfies(
            command ->
                assertThat((java.util.List<String>) command.body().get("roles"))
                    .contains("consultant"));

    // 3) Resume: reopening the link continues at the 2FA step with the stored secret.
    mockMvc
        .perform(get("/users/account-invites/{token}/onboarding", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").value("PENDING_2FA_ACTIVATION"))
        .andExpect(jsonPath("$.twoFactor.secret").value("WIZARDTOTPSECRET"));

    // 4) Two-factor activation consumes the link terminally.
    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/two-factor", token)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"otp\": \"123456\" }"))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/users/account-invites/{token}/onboarding", token))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.reason").value("CONSUMED"));
  }

  @Test
  void unknownToken_answers404() throws Exception {
    mockMvc
        .perform(get("/users/account-invites/{token}/onboarding", "no-such-token"))
        .andExpect(status().isNotFound());
  }

  @Test
  void registerWithTopicOutsideHealthyCoverage_answers400WithoutTouchingTheInvite()
      throws Exception {
    String token = "outside-coverage-token-" + java.util.UUID.randomUUID();
    seedInvite(token);

    // The main contract (#997): AgencyService answers, so the coverage set is authoritative and
    // a topic outside it IS a client error. The invite must stay untouched.
    mockMvc
        .perform(registerWithTopic(token, 999L))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("outside the coverage")));

    assertInviteStillResolvesUnconsumed(token);
  }

  @Test
  void registerWithUnverifiableTopicWhileAgencyIsDown_answers500WithoutTouchingTheInvite()
      throws Exception {
    String token = "degraded-coverage-token-" + java.util.UUID.randomUUID();
    seedInvite(token);
    agencyIsDown();

    // With a DEGRADED coverage set the same selection is indeterminate — the topic may well be
    // in the real agency coverage — so the contract (#997 review) answers 5xx (retry), never
    // 400, to avoid misclassifying potentially valid input as a client error during an outage.
    mockMvc.perform(registerWithTopic(token, 999L)).andExpect(status().isInternalServerError());

    assertInviteStillResolvesUnconsumed(token);
  }

  private MockHttpServletRequestBuilder registerWithTopic(String token, Long topicId) {
    return post("/users/account-invites/{token}/onboarding/register", token)
        .header("X-CSRF-Token", CSRF)
        .cookie(CSRF_COOKIE)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {
              "account": { "username": "codex_wizard_counsellor", "password": "Valid-Test-Password-2026!" },
              "topicIds": [%d]
            }
            """
                .formatted(topicId));
  }

  private void assertInviteStillResolvesUnconsumed(String token) throws Exception {
    mockMvc
        .perform(get("/users/account-invites/{token}/onboarding", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").doesNotExist());
  }

  @Test
  void resolveExpiredInvite_persistsTheExpiredTransitionDespiteTheGoneAnswer() throws Exception {
    String token = "expired-counsellor-token-" + java.util.UUID.randomUUID();
    AccountInvite expired =
        accountInviteRepository.save(
            AccountInvite.builder()
                .targetRole(AccountInviteTargetRole.COUNSELLOR)
                .tenantId(79L)
                .recipientEmail("lisa.simpson@example.org")
                .firstName("Lisa")
                .lastName("Simpson")
                .agencyId(AGENCY_ID)
                .departmentId(DEPARTMENT_TOPIC_ID)
                .tokenHash(sha256(token))
                .expiresAt(LocalDateTime.now().minusDays(1))
                .status(AccountInviteStatus.EMAIL_SENT)
                .emailVerificationStatus(EmailVerificationStatus.PENDING)
                .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
                .createDate(LocalDateTime.now().minusDays(8))
                .build());

    seededInviteIds.add(expired.getId());

    mockMvc
        .perform(get("/users/account-invites/{token}/onboarding", token))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.reason").value("EXPIRED"));

    // The EXPIRED transition must survive the thrown link-death exception (noRollbackFor) —
    // without it the row would stay EMAIL_SENT forever.
    assertThat(accountInviteRepository.findById(expired.getId()))
        .hasValueSatisfying(
            persisted -> assertThat(persisted.getStatus()).isEqualTo(AccountInviteStatus.EXPIRED));
  }

  @Test
  void registerWithoutTopics_intoAnExistingSingleTopicAgency_attachesToItWithItsOnlyTopic()
      throws Exception {
    String token = "existing-single-topic-token-" + java.util.UUID.randomUUID();
    seedInvite(token);
    when(agencyService.getAgencyWithoutCaching(AGENCY_ID))
        .thenReturn(
            new AgencyDTO().id(AGENCY_ID).tenantId(79L).topicIds(List.of(DEPARTMENT_TOPIC_ID)));

    mockMvc.perform(registerWithoutTopics(token)).andExpect(status().isOk());

    new org.springframework.transaction.support.TransactionTemplate(fixtureTransactions)
        .executeWithoutResult(
            status -> {
              var consultant =
                  de.caritas.cob.userservice.api.tenant.Tenants.in(
                      79L, () -> consultantRepository.findById(CONSULTANT_ID).orElseThrow());
              assertThat(consultant.getConsultantTopics())
                  .extracting(de.caritas.cob.userservice.api.model.ConsultantTopic::getTopicId)
                  .containsExactly(DEPARTMENT_TOPIC_ID);
              assertThat(consultant.getConsultantAgencies())
                  .extracting(de.caritas.cob.userservice.api.model.ConsultantAgency::getAgencyId)
                  .containsExactly(AGENCY_ID);
            });
  }

  @Test
  void registerWithoutTopics_whenTheAgencyOffersSeveralTopics_answers400() throws Exception {
    String token = "existing-multi-topic-token-" + java.util.UUID.randomUUID();
    seedInvite(token);

    mockMvc
        .perform(registerWithoutTopics(token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("At least one topic")));

    assertInviteStillResolvesUnconsumed(token);
  }

  private MockHttpServletRequestBuilder registerWithoutTopics(String token) {
    return post("/users/account-invites/{token}/onboarding/register", token)
        .header("X-CSRF-Token", CSRF)
        .cookie(CSRF_COOKIE)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {
              "account": { "username": "codex_wizard_counsellor", "password": "Valid-Test-Password-2026!" },
              "topicIds": []
            }
            """);
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
