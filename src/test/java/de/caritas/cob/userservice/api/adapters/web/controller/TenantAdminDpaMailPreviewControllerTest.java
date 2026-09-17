package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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

@ExtendWith(MockitoExtension.class)
class TenantAdminDpaMailPreviewControllerTest {

  @Mock private AccountInviteService accountInviteService;
  @Mock private DpaForwardEmailService dpaForwardEmailService;

  private TenantAdminDpaMailPreviewController controller;

  @BeforeEach
  void setUp() {
    controller =
        new TenantAdminDpaMailPreviewController(accountInviteService, dpaForwardEmailService);
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
  void preview_consumedInvite_rejectsWithoutCallingCanonicalRenderer() {
    when(accountInviteService.findInviteByToken("accepted-token"))
        .thenReturn(
            invite(AccountInviteTargetRole.TENANT_ADMIN, AccountInviteStatus.ACCEPTED, 42L));

    assertThatThrownBy(() -> controller.preview("accepted-token"))
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
