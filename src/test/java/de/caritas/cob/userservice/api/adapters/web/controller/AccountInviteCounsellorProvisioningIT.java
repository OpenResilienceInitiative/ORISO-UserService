package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.TaskIdentityGrant;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyFacts;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import de.caritas.cob.userservice.api.testHelper.BoundedIdentityHttpFixtures;
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
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@WithTenant(1L)
class AccountInviteCounsellorProvisioningIT
    extends de.caritas.cob.userservice.api.testHelper.AccountInactivityPolicyHttpFixture {

  @MockitoBean(name = "keycloakRestTemplate")
  private RestTemplate boundedIdentityHttp;

  @MockitoBean(name = "restTemplate")
  private RestTemplate taskAuthHttp;

  @MockitoBean private JwtDecoder taskDecoder;
  @MockitoBean private TaskIdentityGrant taskIdentityGrant;
  @Autowired private TaskIdentityConfiguration taskIdentities;
  @Autowired private Environment identityEnvironment;
  @Autowired private ObjectMapper identityMapper;
  private BoundedIdentityHttpFixtures.Provider nativeAccounts;
  private final java.util.List<String> createdIdentityIds = new java.util.ArrayList<>();
  @Autowired private org.springframework.jdbc.core.JdbcTemplate fixtureJdbc;
  @Autowired private org.springframework.transaction.PlatformTransactionManager fixtureTransactions;

  private void givenTaskAccounts() {
    nativeAccounts =
        BoundedIdentityHttpFixtures.givenProvider(
            boundedIdentityHttp,
            taskIdentityGrant,
            taskIdentities,
            identityEnvironment,
            identityMapper,
            body -> java.util.UUID.randomUUID().toString(),
            createdIdentityIds::add);
    BoundedIdentityHttpFixtures.givenTaskGrants(taskAuthHttp, taskDecoder, taskIdentities);
    BoundedIdentityHttpFixtures.givenWizardPolicy(
        taskAuthHttp, taskIdentities, identityEnvironment, identityMapper);
    BoundedIdentityHttpFixtures.givenOtp(
        boundedIdentityHttp,
        taskIdentities,
        new de.caritas.cob.userservice.api.model.OtpInfoDTO()
            .otpSetup(false)
            .otpSecret("SECRET")
            .otpSecretQrCode("QR")
            .otpType(de.caritas.cob.userservice.api.model.OtpType.APP));
    BoundedIdentityHttpFixtures.givenConsultingPolicy(
        taskAuthHttp,
        new de.caritas.cob.userservice.consultingtypeservice.generated.web.model
                .ExtendedConsultingTypeResponseDTO()
            .id(1)
            .consultantBoundedToConsultingType(false));
  }

  @org.junit.jupiter.api.AfterEach
  void clearCreatedIdentityRows() {
    createdIdentityIds.forEach(
        id ->
            de.caritas.cob.userservice.api.testHelper.ExistingAccountSetupFixtureCleanup.consultant(
                fixtureJdbc, fixtureTransactions, id));
    createdIdentityIds.clear();
  }

  private static final String RAW_TOKEN = "emailed-counsellor-token";

  /** The public route resolves to the main tenant, as on the single-domain deployment. */
  @MockitoBean private TenantResolverService tenantResolverService;

  @MockitoBean private TenantService tenantService;

  @Autowired private MockMvc mockMvc;

  @Autowired private AccountInviteRepository accountInviteRepository;

  @MockitoBean private AgencyFacts agencyFacts;
  @MockitoBean private de.caritas.cob.userservice.api.service.agency.AgencyService agencyService;
  @Autowired private de.caritas.cob.userservice.api.port.out.ConsultantRepository consultants;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository consultantAgencies;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository consultantTopics;

  @BeforeEach
  void configureConsultantProvisioning() {
    givenTaskAccounts();
    accountInviteRepository.deleteAll();
    when(agencyFacts.find(275L)).thenAnswer(invocation -> agencyAsSeenByServiceToken(false));
    var agency =
        new de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO()
            .id(275L)
            .tenantId(79L)
            .consultingType(1)
            .topicIds(List.of(2L))
            .teamAgency(false);
    when(agencyService.getPublicImportAgency(275L, 79L)).thenReturn(agency);
    when(agencyService.getAgencyWithoutCaching(275L)).thenReturn(agency);
    when(agencyService.getAgenciesWithoutCaching(any())).thenReturn(List.of(agency));
    when(tenantService.getRestrictedTenantDataFresh(anyLong()))
        .thenReturn(de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures.tenant());
  }

  @Test
  void acceptingEmailedCounsellorInviteCreatesRoutedLoginAccount() throws Exception {
    saveEmailedInvite();

    mockMvc
        .perform(acceptRequest())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inviteStatus").value("ACCEPTED"))
        .andExpect(jsonPath("$.provisioningStatus").value("COMPLETED"))
        .andExpect(jsonPath("$.provisionedUserId").isNotEmpty())
        .andExpect(jsonPath("$.tenantId").value(79))
        .andExpect(jsonPath("$.agencyId").value(275))
        .andExpect(jsonPath("$.departmentId").value(2));

    String id = createdIdentityIds.getLast();
    var consultant =
        de.caritas.cob.userservice.api.tenant.Tenants.in(
            79L, () -> consultants.findById(id).orElseThrow());
    assertThat(
            new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
                .decodeUsername(consultant.getUsername()))
        .isEqualTo("codex_invited_counsellor");
    assertThat(consultant.getEmail()).isEqualTo("lisa.simpson@example.org");
    assertThat(consultant.getTenantId()).isEqualTo(79L);
    de.caritas.cob.userservice.api.tenant.Tenants.in(
        79L,
        () -> {
          assertThat(consultantTopics.findTopicIdsByConsultantId(id)).containsExactly(2L);
          assertThat(consultantAgencies.findByConsultantIdAndDeleteDateIsNull(id))
              .extracting(de.caritas.cob.userservice.api.model.ConsultantAgency::getAgencyId)
              .containsExactly(275L);
          return null;
        });
    assertThat(nativeAccounts.projections().get(id).roles()).containsExactly("consultant");
    assertThat(TechnicalAccessTokenContext.get()).isEmpty();
  }

  /** ORISO-Admin#1026 P2-3: the agency may be soft-deleted between invite and accept. */
  @Test
  void acceptingInviteForAgencyDeletedSinceTheInviteCreatesNothing() throws Exception {
    doAnswer(invocation -> agencyAsSeenByServiceToken(true)).when(agencyFacts).find(275L);
    saveEmailedInvite();

    mockMvc.perform(acceptRequest()).andExpect(status().isNotFound());

    verify(agencyFacts).find(275L);
    assertNothingProvisioned();
  }

  @Test
  void acceptingInviteForAgencyThatNoLongerExistsCreatesNothing() throws Exception {
    doReturn(Optional.empty()).when(agencyFacts).find(anyLong());
    saveEmailedInvite();

    mockMvc.perform(acceptRequest()).andExpect(status().isNotFound());

    assertNothingProvisioned();
  }

  @Test
  void acceptingInviteForAgencyOfAnotherTenantCreatesNothing() throws Exception {
    // The service token sees every tenant, unlike the inviting admin's token.
    doReturn(Optional.of(new AgencyFacts.Agency(275L, 80L, false, List.of(2L))))
        .when(agencyFacts)
        .find(275L);
    saveEmailedInvite();

    mockMvc.perform(acceptRequest()).andExpect(status().isNotFound());

    assertNothingProvisioned();
  }

  private void assertNothingProvisioned() {
    assertThat(nativeAccounts.commands()).isEmpty();
    assertThat(createdIdentityIds).isEmpty();
    assertThat(accountInviteRepository.findAll())
        .singleElement()
        .satisfies(
            invite -> {
              assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
              assertThat(invite.getProvisionedUserId()).isNull();
            });
  }

  /** The caller is anonymous, so the agency is only readable with the service token. */
  private static Optional<AgencyFacts.Agency> agencyAsSeenByServiceToken(boolean deleted) {
    assertThat(TechnicalAccessTokenContext.get()).contains("synthetic-task-CONFIG_WIZARD");
    return Optional.of(new AgencyFacts.Agency(275L, 79L, deleted, List.of(2L)));
  }

  private static org.springframework.test.web.servlet.RequestBuilder acceptRequest() {
    return post("/users/account-invites/{token}/accept", RAW_TOKEN)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {
              "username": "codex_invited_counsellor",
              "password": "Valid-Test-Password-2026!",
              "formalLanguage": true
            }
            """);
  }

  private void saveEmailedInvite() throws Exception {
    accountInviteRepository.save(
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .tenantId(79L)
            .recipientEmail("lisa.simpson@example.org")
            .firstName("Lisa")
            .lastName("Simpson")
            .agencyId(275L)
            .departmentId(2L)
            .tokenHash(sha256(RAW_TOKEN))
            .expiresAt(LocalDateTime.now().plusDays(1))
            .status(AccountInviteStatus.EMAIL_SENT)
            .emailVerificationStatus(EmailVerificationStatus.PENDING)
            .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
            .createDate(LocalDateTime.now())
            .build());
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
