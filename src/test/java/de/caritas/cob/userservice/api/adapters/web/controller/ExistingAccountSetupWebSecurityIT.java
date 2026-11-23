package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInvitePurpose;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.ExistingAccountSetupService;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The mailed token reaches setup anonymously without weakening adjacent browser POSTs. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@WithTenant(1L)
class ExistingAccountSetupWebSecurityIT {

  @MockitoBean private TenantResolverService tenantResolverService;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private ExistingAccountSetupService setupService;
  @MockitoBean private AccountInviteService inviteService;

  @Autowired private MockMvc mockMvc;

  @Test
  void mailedSetupLinkWorksOnBothAnonymousRoutesWithoutAnExistingCsrfCookie() throws Exception {
    for (String prefix : new String[] {"", "/service"}) {
      mockMvc
          .perform(
              post(prefix + "/users/account-invites/{token}/setup", "emailed-token")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"password\":\"chosen-permanent-password\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.phase").value("COMPLETED"));
    }
    verify(setupService, org.mockito.Mockito.times(2))
        .confirm("emailed-token", "chosen-permanent-password");
  }

  @Test
  void aSiblingPostDoesNotInheritTheTokenRouteCsrfExemption() throws Exception {
    mockMvc
        .perform(post("/users/account-invites/emailed-token/setup/other"))
        .andExpect(status().isForbidden());
  }

  @Test
  void expiredSetupTokenReturnsNamedGoneWithoutCallingPasswordMutation() throws Exception {
    doThrow(new AccountInviteLinkException(AccountInviteLinkException.Reason.EXPIRED))
        .when(setupService)
        .confirm("dead-token", "new-permanent-password");

    mockMvc
        .perform(
            post("/users/account-invites/{token}/setup", "dead-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"new-permanent-password\"}"))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.reason").value("EXPIRED"));
  }

  @Test
  void anonymousInviteHistoryResendCannotIssueAnExistingAccountSetupLink() throws Exception {
    mockMvc
        .perform(
            post("/useradmin/account-invites/10/resend")
                .cookie(new Cookie("CSRF-TOKEN", "test"))
                .header("X-CSRF-Token", "test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(inviteService);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_ADMIN)
  void authorisedInviteHistoryResendUsesTheExistingResponseWithoutExposingAToken()
      throws Exception {
    var replacement =
        AccountInvite.builder()
            .id(12L)
            .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
            .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
            .recipientEmail("admin@example.org")
            .status(AccountInviteStatus.EMAIL_SENT)
            .build();
    org.mockito.Mockito.when(inviteService.resendInvite(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new AccountInviteService.InviteSendResult(replacement, null, null, null));

    mockMvc
        .perform(
            post("/useradmin/account-invites/10/resend")
                .cookie(new Cookie("CSRF-TOKEN", "test"))
                .header("X-CSRF-Token", "test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(12))
        .andExpect(jsonPath("$.onboardingPurpose").value("EXISTING_ACCOUNT_SETUP"))
        .andExpect(jsonPath("$.rawToken").isEmpty())
        .andExpect(jsonPath("$.acceptUrl").isEmpty());
    var command = org.mockito.ArgumentCaptor.forClass(AccountInviteService.SendInviteCommand.class);
    verify(inviteService).resendInvite(command.capture());
    org.assertj.core.api.Assertions.assertThat(command.getValue().inviteId()).isEqualTo(10L);
    org.assertj.core.api.Assertions.assertThat(command.getValue().templateId()).isNull();
  }
}
