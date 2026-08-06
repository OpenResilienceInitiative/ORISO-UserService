package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService;
import de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService.DpaForwardEmailCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
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

  @PreAuthorize(ADMIN_AUTH)
  @PostMapping("/email")
  public ResponseEntity<Void> forwardSigningLink(
      @Valid @RequestBody DpaForwardEmailRequest request) {
    dpaForwardEmailService.sendSigningLink(
        new DpaForwardEmailCommand(
            request.tenantId, request.recipientEmail, request.signLink, request.expiresAt));
    return ResponseEntity.noContent().build();
  }

  @Data
  public static class DpaForwardEmailRequest {
    @NotNull private Long tenantId;
    @NotBlank @Email private String recipientEmail;
    @NotBlank private String signLink;
    @NotNull private LocalDateTime expiresAt;
  }
}
