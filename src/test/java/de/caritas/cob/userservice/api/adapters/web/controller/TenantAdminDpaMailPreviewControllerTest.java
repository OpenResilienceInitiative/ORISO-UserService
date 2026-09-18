package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService;
import de.caritas.cob.userservice.api.service.notification.DpaSigningEmailPreview;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class TenantAdminDpaMailPreviewControllerTest {

  @Mock private AccountInviteService accountInviteService;
  @Mock private DpaForwardEmailService dpaForwardEmailService;

  private TenantAdminDpaMailPreviewController controller;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    controller =
        new TenantAdminDpaMailPreviewController(accountInviteService, dpaForwardEmailService);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void preview_validTenantAdminInvite_returnsCanonicalMailWithoutInviteMutation() {
    when(accountInviteService.findInviteByToken("valid-token"))
        .thenReturn(
            invite(AccountInviteTargetRole.TENANT_ADMIN, AccountInviteStatus.EMAIL_SENT, 42L));
    when(dpaForwardEmailService.previewSigningMail(42L))
        .thenReturn(new DpaSigningEmailPreview("Vertragsunterlagen", "<p>preview</p>"));

    var response = controller.preview("valid-token");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody())
        .isEqualTo(
            new TenantAdminDpaMailPreviewController.DpaSigningMailPreviewResponse(
                "Vertragsunterlagen", "<p>preview</p>"));
    verify(accountInviteService).findInviteByToken("valid-token");
    verify(dpaForwardEmailService).previewSigningMail(42L);
    verifyNoMoreInteractions(accountInviteService, dpaForwardEmailService);
  }

  @Test
  void preview_expiredInvite_rejectsWithoutCallingCanonicalRenderer() {
    when(accountInviteService.findInviteByToken("expired-token"))
        .thenReturn(
            invite(
                AccountInviteTargetRole.TENANT_ADMIN,
                AccountInviteStatus.EMAIL_SENT,
                42L,
                LocalDateTime.now().minusMinutes(1)));

    assertThatThrownBy(() -> controller.preview("expired-token"))
        .isInstanceOf(BadRequestException.class);

    verify(dpaForwardEmailService, never()).previewSigningMail(42L);
  }

  @Test
  void preview_terminalInvite_rejectsWithoutCallingCanonicalRenderer() {
    when(accountInviteService.findInviteByToken("revoked-token"))
        .thenReturn(invite(AccountInviteTargetRole.TENANT_ADMIN, AccountInviteStatus.REVOKED, 42L));

    assertThatThrownBy(() -> controller.preview("revoked-token"))
        .isInstanceOf(BadRequestException.class);

    verify(dpaForwardEmailService, never()).previewSigningMail(42L);
  }

  @Test
  void preview_nonTenantAdminInvite_isNotExposedOrRendered() {
    when(accountInviteService.findInviteByToken("other-role-token"))
        .thenReturn(
            invite(AccountInviteTargetRole.AGENCY_ADMIN, AccountInviteStatus.EMAIL_SENT, 42L));

    assertThatThrownBy(() -> controller.preview("other-role-token"))
        .isInstanceOf(NotFoundException.class);

    verify(dpaForwardEmailService, never()).previewSigningMail(42L);
  }

  @Test
  void preview_unknownToken_isNotRendered() {
    when(accountInviteService.findInviteByToken("unknown-token"))
        .thenThrow(new NotFoundException("Account invite not found"));

    assertThatThrownBy(() -> controller.preview("unknown-token"))
        .isInstanceOf(NotFoundException.class);

    verify(dpaForwardEmailService, never()).previewSigningMail(42L);
  }

  /**
   * The DPA step sits AFTER account registration in the wizard, and {@code registerTenantAdmin}
   * claims the invite via {@code claimForAcceptance}, which moves it to ACCEPTED. The two-factor
   * guard states the same lifecycle from the other side: it rejects EMAIL_SENT with "Registration
   * has not happened yet for this invite". So by the time the forward dialog opens, ACCEPTED is the
   * ordinary state — requiring EMAIL_SENT here made the preview fail for every real onboarding.
   */
  @Test
  void preview_registeredInviteAtTheDpaStep_rendersTheMail() {
    when(accountInviteService.findInviteByToken("registered-token"))
        .thenReturn(
            invite(AccountInviteTargetRole.TENANT_ADMIN, AccountInviteStatus.ACCEPTED, 42L));
    when(dpaForwardEmailService.previewSigningMail(42L))
        .thenReturn(new DpaSigningEmailPreview("Vertragsunterlagen", "<p>preview</p>"));

    var response = controller.preview("registered-token");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    verify(dpaForwardEmailService).previewSigningMail(42L);
  }

  /**
   * The Admin frontend calls the gateway path with the `/service` prefix (ORISO-Admin
   * `src/api/tenantOnboarding/dpaMailPreview.ts`), exactly like every neighbouring onboarding
   * endpoint. Routing is the seam here: the unit tests above call the handler directly and so
   * cannot see a missing mapping, which is how the forward dialog ended up showing "preview could
   * not be rendered" instead of the mail.
   */
  @Test
  void preview_isServedOnTheGatewayPathTheAdminFrontendCalls() throws Exception {
    when(accountInviteService.findInviteByToken("valid-token"))
        .thenReturn(
            invite(AccountInviteTargetRole.TENANT_ADMIN, AccountInviteStatus.EMAIL_SENT, 42L));
    when(dpaForwardEmailService.previewSigningMail(42L))
        .thenReturn(new DpaSigningEmailPreview("Vertragsunterlagen", "<p>preview</p>"));

    mockMvc
        .perform(get("/service/users/account-invites/valid-token/onboarding/dpa-mail-preview"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").value("Vertragsunterlagen"))
        .andExpect(jsonPath("$.html").value("<p>preview</p>"));
  }

  private static AccountInvite invite(
      AccountInviteTargetRole role, AccountInviteStatus status, Long tenantId) {
    return invite(role, status, tenantId, LocalDateTime.now().plusDays(1));
  }

  private static AccountInvite invite(
      AccountInviteTargetRole role,
      AccountInviteStatus status,
      Long tenantId,
      LocalDateTime expiresAt) {
    return AccountInvite.builder()
        .targetRole(role)
        .status(status)
        .tenantId(tenantId)
        .expiresAt(expiresAt)
        .build();
  }
}
