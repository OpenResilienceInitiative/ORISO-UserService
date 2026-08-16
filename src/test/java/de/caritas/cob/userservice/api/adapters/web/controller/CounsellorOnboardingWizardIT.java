package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * creation itself runs through the SAME facade the normal admin form uses (mocked at the facade
 * seam exactly like {@link AccountInviteCounsellorProvisioningIT}); external identity lookups
 * (Keycloak OTP material) are mocked at their ports.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CounsellorOnboardingWizardIT {

  private static final String CONSULTANT_ID = "wizard-counsellor-id";

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

  @Autowired private MockMvc mockMvc;

  @Autowired private AccountInviteRepository accountInviteRepository;

  @MockitoBean private ConsultantAdminFacade consultantAdminFacade;

  /**
   * The single {@code keycloakService} bean implements ALL identity ports (authentication, second
   * factor, profile lookup, …), so the concrete bean is mocked — mocking a single port interface
   * would replace the bean and break every other port dependency at context startup.
   */
  @MockitoBean private KeycloakService keycloakService;

  /**
   * The invite's topic coverage comes from AgencyService (#1008 review). Without a stub the
   * unreachable upstream of the {@code testing} profile would silently decide the coverage — and
   * with it the HTTP answers — so every test here states the upstream behaviour it relies on:
   * {@link #agencyIsHealthy()} for the documented contract, {@link #agencyIsDown()} for the outage.
   */
  @MockitoBean private AgencyService agencyService;

  @BeforeEach
  void configureProvisioning() {
    agencyIsHealthy();
    when(consultantAdminFacade.createNewConsultant(any(CreateConsultantDTO.class)))
        .thenReturn(
            new ConsultantAdminResponseDTO().embedded(new ConsultantDTO().id(CONSULTANT_ID)));
    when(keycloakService.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("technical-access-token", 60, 60, "refresh"));
    when(keycloakService.getOtpCredential(anyString()))
        .thenReturn(
            new IdentityOtpCredential(false, "WIZARDTOTPSECRET", "QRBASE64", IdentityOtpType.APP));
    when(keycloakService.setUpOtpCredential(anyString(), eq("123456"), anyString()))
        .thenReturn(true);
    when(keycloakService.findById(CONSULTANT_ID))
        .thenReturn(
            Optional.of(
                new IdentityProfile(
                    CONSULTANT_ID, "codex_wizard_counsellor", "Lisa", "Simpson", "l@oriso.org")));
  }

  /** AgencyService answers: the invite's agency covers the topics 2 (department) and 7. */
  private void agencyIsHealthy() {
    when(agencyService.getAgencyWithoutCaching(AGENCY_ID))
        .thenReturn(new AgencyDTO().id(AGENCY_ID).topicIds(List.of(DEPARTMENT_TOPIC_ID, 7L)));
  }

  /** AgencyService is unreachable — the coverage degrades to the invite's department topic. */
  private void agencyIsDown() {
    when(agencyService.getAgencyWithoutCaching(AGENCY_ID))
        .thenThrow(new IllegalStateException("agency service unreachable"));
  }

  private void seedInvite(String rawToken) throws Exception {
    accountInviteRepository.save(
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .tenantId(79L)
            .recipientEmail("lisa.simpson@oriso.org")
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
        .andExpect(jsonPath("$.recipientEmail").value("lisa.simpson@oriso.org"))
        .andExpect(jsonPath("$.firstName").value("Lisa"))
        .andExpect(jsonPath("$.lastName").value("Simpson"))
        .andExpect(jsonPath("$.tenantId").value(79))
        .andExpect(jsonPath("$.agencyId").value(275))
        .andExpect(jsonPath("$.departmentId").value(2))
        .andExpect(jsonPath("$.topics[0].id").value(2))
        .andExpect(jsonPath("$.topics[1].id").value(7))
        .andExpect(jsonPath("$.topics.length()").value(2))
        .andExpect(jsonPath("$.phase").doesNotExist());

    // 2) Register with the wizard fields. The selection deliberately includes the AGENCY-ONLY
    // topic 7 next to the routed department topic 2 (#1008 review): a regression that offers the
    // agency coverage on resolve but drops or rejects it on register would otherwise pass here.
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
                      "topicIds": [2, 7]
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consultantId").value(CONSULTANT_ID))
        .andExpect(jsonPath("$.phase").value("PENDING_2FA_ACTIVATION"))
        .andExpect(jsonPath("$.twoFactor.secret").value("WIZARDTOTPSECRET"))
        .andExpect(jsonPath("$.twoFactor.qrCodeBase64").value("QRBASE64"));

    // The registration went through the SAME domain path as the normal admin creation.
    ArgumentCaptor<CreateConsultantDTO> consultantCaptor =
        ArgumentCaptor.forClass(CreateConsultantDTO.class);
    verify(consultantAdminFacade).createNewConsultant(consultantCaptor.capture());
    CreateConsultantDTO consultant = consultantCaptor.getValue();
    assertThat(consultant.getUsername()).isEqualTo("codex_wizard_counsellor");
    assertThat(consultant.getEmail()).isEqualTo("lisa.simpson@oriso.org");
    assertThat(consultant.getTenantId()).isEqualTo(79L);
    // Both topics reach the provisioning command — the agency-only topic 7 included.
    assertThat(consultant.getTopicIds()).containsExactly(2L, 7L);
    assertThat(consultant.getSalutation()).isEqualTo("counsellor_female");
    assertThat(consultant.getPosition()).isEqualTo("Head of centre");
    assertThat(consultant.getTitle()).isEqualTo("Dipl.-Soz.Päd.");
    assertThat(consultant.getDisplayName()).isEqualTo("Lisa");
    assertThat(consultant.getInternalDisplayName()).isEqualTo("Lisa S. (Nord)");

    ArgumentCaptor<CreateConsultantAgencyDTO> agencyCaptor =
        ArgumentCaptor.forClass(CreateConsultantAgencyDTO.class);
    verify(consultantAdminFacade)
        .createNewConsultantAgency(eq(CONSULTANT_ID), agencyCaptor.capture());
    assertThat(agencyCaptor.getValue().getAgencyId()).isEqualTo(AGENCY_ID);
    assertThat(agencyCaptor.getValue().getRoleSetKey()).isEqualTo("CONSULTANT_DEFAULT");

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
                .recipientEmail("lisa.simpson@oriso.org")
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

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
