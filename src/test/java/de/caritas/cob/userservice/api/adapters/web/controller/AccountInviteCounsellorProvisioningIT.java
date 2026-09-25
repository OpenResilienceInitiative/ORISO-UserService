package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@WithTenant(1L)
class AccountInviteCounsellorProvisioningIT {

  private static final String RAW_TOKEN = "emailed-counsellor-token";

  /** The public route resolves to the main tenant, as on the single-domain deployment. */
  @MockitoBean private TenantResolverService tenantResolverService;

  @MockitoBean private TenantService tenantService;

  @Autowired private MockMvc mockMvc;

  @Autowired private AccountInviteRepository accountInviteRepository;

  @MockitoBean private ConsultantAdminFacade consultantAdminFacade;

  @MockitoBean
  private de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation
          .ConsultantAgencyRelationCreatorService
      consultantAgencyRelationCreatorService;

  @MockitoBean private AgencyFacts agencyFacts;

  @BeforeEach
  void configureConsultantProvisioning() {
    accountInviteRepository.deleteAll();
    when(agencyFacts.find(275L)).thenAnswer(invocation -> agencyAsSeenByServiceToken(false));
    when(consultantAdminFacade.createNewConsultant(any(CreateConsultantDTO.class)))
        .thenAnswer(
            invocation -> {
              // Remote provisioning calls run with the service token (KeycloakTestConfig), and
              // only those (ORISO-Helm#367).
              assertThat(TechnicalAccessTokenContext.get()).contains("synthetic-service-token");
              return new ConsultantAdminResponseDTO()
                  .embedded(new ConsultantDTO().id("provisioned-counsellor-id"));
            });
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

    ArgumentCaptor<CreateConsultantDTO> consultantCaptor =
        ArgumentCaptor.forClass(CreateConsultantDTO.class);
    verify(consultantAdminFacade).createNewConsultant(consultantCaptor.capture());
    CreateConsultantDTO consultant = consultantCaptor.getValue();
    assertThat(consultant.getUsername()).isEqualTo("codex_invited_counsellor");
    assertThat(consultant.getEmail()).isEqualTo("lisa.simpson@oriso.org");
    assertThat(consultant.getTenantId()).isEqualTo(79L);
    assertThat(consultant.getTopicIds()).containsExactly(2L);

    ArgumentCaptor<CreateConsultantAgencyDTO> agencyCaptor =
        ArgumentCaptor.forClass(CreateConsultantAgencyDTO.class);
    verify(consultantAgencyRelationCreatorService)
        .createNewConsultantAgency(eq("provisioned-counsellor-id"), agencyCaptor.capture());
    assertThat(agencyCaptor.getValue().getAgencyId()).isEqualTo(275L);
    assertThat(agencyCaptor.getValue().getRoleSetKey()).isEqualTo("CONSULTANT_DEFAULT");
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
    verify(consultantAdminFacade, never()).createNewConsultant(any(CreateConsultantDTO.class));
    verify(consultantAgencyRelationCreatorService, never())
        .createNewConsultantAgency(any(), any(CreateConsultantAgencyDTO.class));
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
    assertThat(TechnicalAccessTokenContext.get()).contains("synthetic-service-token");
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
            .recipientEmail("lisa.simpson@oriso.org")
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
