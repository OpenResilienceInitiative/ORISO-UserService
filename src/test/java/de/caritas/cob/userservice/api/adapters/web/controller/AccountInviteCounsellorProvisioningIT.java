package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
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
class AccountInviteCounsellorProvisioningIT {

  private static final String RAW_TOKEN = "emailed-counsellor-token";

  @Autowired private MockMvc mockMvc;

  @Autowired private AccountInviteRepository accountInviteRepository;

  @MockitoBean private ConsultantAdminFacade consultantAdminFacade;

  @BeforeEach
  void configureConsultantProvisioning() {
    when(consultantAdminFacade.createNewConsultant(any(CreateConsultantDTO.class)))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id("provisioned-counsellor-id")));
  }

  @Test
  void acceptingEmailedCounsellorInviteCreatesRoutedLoginAccount() throws Exception {
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

    mockMvc
        .perform(
            post("/users/account-invites/{token}/accept", RAW_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "username": "codex_invited_counsellor",
                      "password": "Valid-Test-Password-2026!",
                      "formalLanguage": true
                    }
                    """))
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
    verify(consultantAdminFacade)
        .createNewConsultantAgency(eq("provisioned-counsellor-id"), agencyCaptor.capture());
    assertThat(agencyCaptor.getValue().getAgencyId()).isEqualTo(275L);
    assertThat(agencyCaptor.getValue().getRoleSetKey()).isEqualTo("CONSULTANT_DEFAULT");
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
