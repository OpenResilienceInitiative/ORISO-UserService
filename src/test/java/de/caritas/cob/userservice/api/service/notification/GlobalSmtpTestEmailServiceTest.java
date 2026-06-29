package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSmtpTestEmailDTO;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GlobalSmtpTestEmailServiceTest {

  @Mock private ApplicationSettingsService applicationSettingsService;

  @InjectMocks private GlobalSmtpTestEmailService service;

  @Test
  void sendTestEmail_Should_ThrowIllegalState_When_SmtpCredentialsNotConfigured() {
    when(applicationSettingsService.getGlobalSmtpCredentials()).thenReturn(Optional.empty());

    GlobalSmtpTestEmailDTO dto = new GlobalSmtpTestEmailDTO();
    dto.setHost("smtp.invalid");
    dto.setPort(587);
    dto.setSecure(false);
    dto.setFrom("from@example.com");
    dto.setRecipientEmail("to@example.com");

    assertThatThrownBy(() -> service.sendTestEmail(dto))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMTP credentials");
  }
}
