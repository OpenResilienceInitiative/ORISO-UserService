package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService;
import de.caritas.cob.userservice.api.service.notification.DpaSigningEmailPreview;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public, token-bound, read-only preview for the tenant-admin onboarding forward dialog. */
@RestController
@RequiredArgsConstructor
public class TenantAdminDpaMailPreviewController {

  private static final Set<AccountInviteStatus> PREVIEWABLE_STATUSES =
      EnumSet.of(AccountInviteStatus.EMAIL_SENT, AccountInviteStatus.ACCEPTED);

  private final @NonNull AccountInviteService accountInviteService;
  private final @NonNull DpaForwardEmailService dpaForwardEmailService;

  @GetMapping({
    "/users/account-invites/{token}/onboarding/dpa-mail-preview",
    "/service/users/account-invites/{token}/onboarding/dpa-mail-preview"
  })
  public ResponseEntity<DpaSigningMailPreviewResponse> preview(@PathVariable String token) {
    AccountInvite invite = accountInviteService.findInviteByToken(token);
    validatePreviewableTenantAdminInvite(invite);
    DpaSigningEmailPreview preview =
        dpaForwardEmailService.previewSigningMail(invite.getTenantId());
    return ResponseEntity.ok(new DpaSigningMailPreviewResponse(preview.subject(), preview.html()));
  }

  private static void validatePreviewableTenantAdminInvite(AccountInvite invite) {
    if (invite.getTargetRole() != AccountInviteTargetRole.TENANT_ADMIN) {
      throw new NotFoundException("Account invite not found");
    }
    // The DPA step runs after registration, which claims the invite into ACCEPTED, so both the
    // not-yet-registered and the registered state are legitimate here — compare the two-factor
    // guard in TenantAdminOnboardingService, which treats ACCEPTED as the post-registration
    // norm. Only terminal links (revoked, superseded, expired) are refused. Rendering is
    // read-only: it mints no sign link and sends no mail.
    if (!PREVIEWABLE_STATUSES.contains(invite.getStatus())
        || invite.getExpiresAt() == null
        || invite.getExpiresAt().isBefore(LocalDateTime.now())) {
      throw new BadRequestException("Account invite is not active");
    }
  }

  public record DpaSigningMailPreviewResponse(String subject, String html) {}
}
