package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
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

  @BeforeEach
  void configureProvisioning() {
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

  private void seedInvite(String rawToken) throws Exception {
    accountInviteRepository.save(
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .tenantId(79L)
            .recipientEmail("lisa.simpson@oriso.org")
            .firstName("Lisa")
            .lastName("Simpson")
            .agencyId(275L)
            .departmentId(2L)
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

    // 1) Resolve: prefill data + the invite's topic coverage (department routing fallback —
    // AgencyService is unreachable in the testing profile, which must NOT break the flow).
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

    // The registration went through the SAME domain path as the normal admin creation.
    ArgumentCaptor<CreateConsultantDTO> consultantCaptor =
        ArgumentCaptor.forClass(CreateConsultantDTO.class);
    verify(consultantAdminFacade).createNewConsultant(consultantCaptor.capture());
    CreateConsultantDTO consultant = consultantCaptor.getValue();
    assertThat(consultant.getUsername()).isEqualTo("codex_wizard_counsellor");
    assertThat(consultant.getEmail()).isEqualTo("lisa.simpson@oriso.org");
    assertThat(consultant.getTenantId()).isEqualTo(79L);
    assertThat(consultant.getTopicIds()).containsExactly(2L);
    assertThat(consultant.getSalutation()).isEqualTo("counsellor_female");
    assertThat(consultant.getPosition()).isEqualTo("Head of centre");
    assertThat(consultant.getTitle()).isEqualTo("Dipl.-Soz.Päd.");
    assertThat(consultant.getDisplayName()).isEqualTo("Lisa");
    assertThat(consultant.getInternalDisplayName()).isEqualTo("Lisa S. (Nord)");

    ArgumentCaptor<CreateConsultantAgencyDTO> agencyCaptor =
        ArgumentCaptor.forClass(CreateConsultantAgencyDTO.class);
    verify(consultantAdminFacade)
        .createNewConsultantAgency(eq(CONSULTANT_ID), agencyCaptor.capture());
    assertThat(agencyCaptor.getValue().getAgencyId()).isEqualTo(275L);
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
  void registerWithTopicOutsideCoverage_isRejectedWithoutTouchingTheInvite() throws Exception {
    String token = "outside-coverage-token-" + java.util.UUID.randomUUID();
    seedInvite(token);

    // AgencyService is unreachable in the testing profile, so the coverage set is DEGRADED to
    // the department topic. A topic outside a degraded set is indeterminate — the contract
    // (#997 review) answers 5xx (retry), never 400, to avoid misclassifying potentially valid
    // input as a client error during an outage. Either way the invite must stay untouched.
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
                      "topicIds": [999]
                    }
                    """))
        .andExpect(status().isInternalServerError());

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
                .agencyId(275L)
                .departmentId(2L)
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
