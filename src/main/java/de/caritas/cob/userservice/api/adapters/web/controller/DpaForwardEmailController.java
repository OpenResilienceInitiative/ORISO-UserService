package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService;
import de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService.DpaForwardEmailCommand;
import de.caritas.cob.userservice.api.service.notification.DpaSigningEmailPreview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/useradmin/dpa-invites")
public class DpaForwardEmailController {

  private static final String ADMIN_AUTH =
      "hasAnyAuthority('AUTHORIZATION_TENANT_ADMIN', 'AUTHORIZATION_USER_ADMIN')";

  private final @NonNull DpaForwardEmailService dpaForwardEmailService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/email")
  public ResponseEntity<Void> forwardSigningLink(
      @Valid @RequestBody DpaForwardEmailRequest request) {
    dpaForwardEmailService.sendSigningLink(
        new DpaForwardEmailCommand(
            request.tenantId, request.recipientEmail, request.signLink, request.expiresAt));
    return ResponseEntity.noContent().build();
  }

  /**
   * Authenticated no-send rendering for the configured DPA_FORWARD template. The request
   * deliberately carries only the tenant context: preview text and recipient are fixed sample
   * values, so this cannot become an arbitrary invitation-template preview.
   */
  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/preview")
  public ResponseEntity<DpaSigningMailPreviewResponse> previewSigningMail(
      @Valid @RequestBody DpaForwardEmailPreviewRequest request) {
    assertCallerMayPreviewTenant(request.tenantId);
    DpaSigningEmailPreview preview = dpaForwardEmailService.previewSigningMail(request.tenantId);
    return ResponseEntity.ok(new DpaSigningMailPreviewResponse(preview.subject(), preview.html()));
  }

  private void assertCallerMayPreviewTenant(Long tenantId) {
    if (tenantId == null) {
      throw new ForbiddenException("DPA mail previews require a concrete tenant");
    }
    if (authenticatedUser.isPlatformAdmin()) {
      return;
    }
    Long callerTenantId = authenticatedUser.getTenantId();
    if (callerTenantId != null && Objects.equals(callerTenantId, tenantId)) {
      return;
    }
    throw new ForbiddenException("DPA mail previews are limited to the caller's tenant");
  }

  @Data
  public static class DpaForwardEmailRequest {
    @NotNull private Long tenantId;
    @NotBlank @Email private String recipientEmail;
    @NotBlank private String signLink;
    @NotNull private LocalDateTime expiresAt;
  }

  @Data
  public static class DpaForwardEmailPreviewRequest {
    @NotNull private Long tenantId;
  }

  public record DpaSigningMailPreviewResponse(String subject, String html) {}
}
