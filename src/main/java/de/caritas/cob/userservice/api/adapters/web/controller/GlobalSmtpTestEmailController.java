package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSmtpTestEmailDTO;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import de.caritas.cob.userservice.api.service.notification.GlobalSmtpTestEmailService;
import jakarta.mail.AuthenticationFailedException;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/users/system-notification-emails")
public class GlobalSmtpTestEmailController {

  private final @NonNull GlobalSmtpTestEmailService globalSmtpTestEmailService;
  private final @NonNull PlatformSmtpSettingsProvider platformSmtpSettingsProvider;

  @GetMapping("/platform-settings")
  public ResponseEntity<PlatformSmtpSettingsProvider.Summary> getPlatformSettings() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(platformSmtpSettingsProvider.summary());
  }

  @PostMapping("/test")
  public ResponseEntity<Object> sendGlobalSmtpTestEmail(
      @Valid @RequestBody GlobalSmtpTestEmailDTO dto) {
    try {
      globalSmtpTestEmailService.sendTestEmail(dto);
      return new ResponseEntity<>(HttpStatus.OK);
    } catch (Exception ex) {
      log.warn("Global SMTP test email failed ({})", ex.getClass().getSimpleName());
      String reason = "SMTP test mail could not be sent. Please verify your SMTP settings.";
      if (ex instanceof GlobalSmtpTestEmailService.ConfigurationException) {
        reason = ex.getMessage();
      } else if (ex instanceof AuthenticationFailedException) {
        reason =
            "SMTP authentication failed. Please verify deployment SMTP credentials and provider auth policy.";
      }
      return ResponseEntity.badRequest().body(Map.of("message", reason));
    }
  }
}
