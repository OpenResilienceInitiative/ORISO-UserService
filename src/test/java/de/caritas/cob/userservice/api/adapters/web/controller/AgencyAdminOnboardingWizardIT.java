package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@WithTenant(1L)
class AgencyAdminOnboardingWizardIT {

  private static final long TENANT = 79L;
  private static final long AGENCY = 275L;
  private static final long NEW_AGENCY = 276L;
  private static final long TOPIC = 2L;
  private static final String CONSULTANT_ID = "c0a1e5e5-1026-4a4a-9d1e-000000000001";
  private static final String ADMIN_ONLY_ID = "c0a1e5e5-1026-4a4a-9d1e-000000000002";
  private static final String CSRF = "it-csrf-token";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF);

  /** The public route resolves to the main tenant, as on the single-domain deployment. */
  @MockitoBean private TenantResolverService tenantResolverService;

  @MockitoBean private TenantService tenantService;

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private AdminRepository adminRepository;
  @Autowired private AdminAgencyRepository adminAgencyRepository;

  @MockitoBean private ConsultantAdminFacade consultantAdminFacade;

  @MockitoBean
  private de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation
          .ConsultantAgencyRelationCreatorService
      consultantAgencyRelationCreatorService;

  @MockitoBean private KeycloakService keycloakService;
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
                    new AgencyFacts.Agency(invocation.getArgument(0), null, false, List.of())));
    when(agencyService.getAgencyWithoutCaching(AGENCY))
        .thenReturn(new AgencyDTO().id(AGENCY).tenantId(TENANT).topicIds(List.of(TOPIC)));
    when(agencyService.getAgencyWithoutCaching(NEW_AGENCY)).thenReturn(null);
    when(topicService.getAllActiveTopicsMap())
        .thenReturn(Map.of(TOPIC, new TopicDTO().id(TOPIC).name("Sucht")));
    when(consultantAdminFacade.createNewConsultant(any(CreateConsultantDTO.class)))
        .thenReturn(
            new ConsultantAdminResponseDTO().embedded(new ConsultantDTO().id(CONSULTANT_ID)));
    when(keycloakService.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("technical-access-token", 60, 60, "refresh"));
    when(keycloakService.createUser(any(UserDTO.class), anyString(), anyString()))
        .thenReturn(new CreatedIdentity(ADMIN_ONLY_ID));
    when(keycloakService.getOtpCredential(anyString()))
        .thenReturn(
            new IdentityOtpCredential(false, "ADMINTOTPSECRET", "QRBASE64", IdentityOtpType.APP));
    when(keycloakService.setUpOtpCredential(anyString(), eq("123456"), anyString()))
        .thenReturn(true);
    when(keycloakService.findById(ADMIN_ONLY_ID))
        .thenReturn(
            Optional.of(
                new IdentityProfile(ADMIN_ONLY_ID, "admin_only", "Ada", "Lovelace", "a@x.org")));
  }

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
    for (String id : List.of(CONSULTANT_ID, ADMIN_ONLY_ID)) {
      adminAgencyRepository.deleteAll(adminAgencyRepository.findByAdminId(id));
      adminRepository.findById(id).ifPresent(adminRepository::delete);
    }
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

    verify(consultantAdminFacade).createNewConsultant(any(CreateConsultantDTO.class));
    // Read as the invite's Träger: the new admin must have landed in it.
    Admin admin = Tenants.in(TENANT, () -> adminRepository.findById(CONSULTANT_ID).orElseThrow());
    assertThat(admin.getType()).isEqualTo(Admin.AdminType.AGENCY);
    assertThat(adminAgencyRepository.findByAdminIdAndAgencyId(CONSULTANT_ID, AGENCY)).hasSize(1);
    verify(keycloakService).updateRole(CONSULTANT_ID, UserRole.RESTRICTED_AGENCY_ADMIN);
  }

  @Test
  void register_Should_CreateOnlyTheAgencyAdmin_When_TheInviteeSwitchesAlsoCounsellorOff()
      throws Exception {
    String token = seedAgencyAdminInvite(AGENCY, true);

    register(token, "admin_only", false, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").value("PENDING_2FA_ACTIVATION"))
        .andExpect(jsonPath("$.twoFactor.secret").value("ADMINTOTPSECRET"));

    verify(consultantAdminFacade, never()).createNewConsultant(any(CreateConsultantDTO.class));
    // Read as the invite's Träger: the new admin must have landed in it.
    Admin admin = Tenants.in(TENANT, () -> adminRepository.findById(ADMIN_ONLY_ID).orElseThrow());
    assertThat(admin.getType()).isEqualTo(Admin.AdminType.AGENCY);
    assertThat(admin.getTenantId()).isEqualTo(TENANT);
    assertThat(adminAgencyRepository.findByAdminIdAndAgencyId(ADMIN_ONLY_ID, AGENCY)).hasSize(1);
    AccountInvite invite = accountInviteRepository.findAll().get(0);
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
    assertThat(accountInviteRepository.findAll().get(0).getTwoFactorStatus())
        .isEqualTo(TwoFactorGateStatus.ACTIVE);
  }

  @Test
  void register_Should_CreateTheNewAgencyFirst_When_TheAgencyIdIsStillAReservation()
      throws Exception {
    String token = seedAgencyAdminInvite(NEW_AGENCY, false);

    register(token, "admin_new_agency", null, "Beratungsstelle Nord").andExpect(status().isOk());

    verify(agencyCreationClient)
        .createAgencyWithReservedId(eq(NEW_AGENCY), eq("Beratungsstelle Nord"), eq(TENANT), any());
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
    AccountInvite stored = accountInviteRepository.findAll().get(0);
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

    verify(agencyCreationClient, never()).createAgencyWithReservedId(any(), any(), any(), any());
    verify(keycloakService, never()).createUser(any(UserDTO.class), anyString(), anyString());
    assertThat(accountInviteRepository.findAll().get(0).getStatus())
        .isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  @Test
  void register_Should_PassTheTopicToTheNewAgency_When_AFoundingAdminDoesNotCounsel()
      throws Exception {
    String token = seedAgencyAdminInvite(NEW_AGENCY, false);

    register(token, "admin_only", false, "Beratungsstelle Nord").andExpect(status().isOk());

    verify(agencyCreationClient)
        .createAgencyWithReservedId(
            eq(NEW_AGENCY), eq("Beratungsstelle Nord"), eq(TENANT), eq(List.of(TOPIC)));
    verify(consultantAdminFacade, never()).createNewConsultant(any(CreateConsultantDTO.class));
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

  private String seedAgencyAdminInvite(long agencyId, boolean alsoCounsellor) {
    String token = "agency-admin-wizard-" + UUID.randomUUID();
    accountInviteRepository.save(
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.AGENCY_ADMIN)
            .tenantId(TENANT)
            .agencyId(agencyId)
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
    return token;
  }
}
