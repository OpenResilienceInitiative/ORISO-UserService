package de.caritas.cob.userservice.api.service.accountinvite.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

/**
 * Strict-send contract (TEN-INV-U6, #890): either the transport confirms the handover and a receipt
 * is returned, or an {@link SmtpSendException} propagates — including when the global SMTP
 * configuration is unavailable. There is no silent-failure path.
 */
@ExtendWith(MockitoExtension.class)
class InviteMailDispatchServiceTest {

  @Mock private RestTemplate restTemplate;
  @Mock private ApplicationSettingsService applicationSettingsService;
  @Mock private InviteMailTransport inviteMailTransport;

  private InviteMailDispatchService service(String smtpUser, String smtpPassword) {
    return new InviteMailDispatchService(
        restTemplate,
        applicationSettingsService,
        inviteMailTransport,
        "http://consultingtypeservice:8080/service",
        smtpUser,
        smtpPassword);
  }

  private Map<String, Object> completeSettingsPayload() {
    return Map.of(
        "globalFeatureSystemNotificationEmailsEnabled", Map.of("value", true),
        "globalSmtpEnabled", Map.of("value", true),
        "globalSmtpHost", Map.of("value", "smtp.example.org"),
        "globalSmtpPort", Map.of("value", "587"),
        "globalSmtpSecure", Map.of("value", false),
        "globalSmtpFrom", Map.of("value", "noreply@example.org"));
  }

  @Test
  void send_Should_returnTransportReceipt_When_SettingsCompleteAndTransportConfirms() {
    when(restTemplate.getForObject(anyString(), any())).thenReturn(completeSettingsPayload());
    InviteMailSendReceipt receipt = new InviteMailSendReceipt("to@example.org", Instant.now());
    when(inviteMailTransport.send(any(), any(), any(), any())).thenReturn(receipt);

    var result = service("smtp-user", "smtp-pass").send("to@example.org", "subject", "<p>body</p>");

    assertThat(result).isSameAs(receipt);
    verify(inviteMailTransport)
        .send(
            new InviteSmtpSettings(
                "smtp.example.org", 587, false, "smtp-user", "smtp-pass", "noreply@example.org"),
            "to@example.org",
            "subject",
            "<p>body</p>");
  }

  @Test
  void send_Should_throwSmtpSendException_When_SmtpDisabled() {
    when(restTemplate.getForObject(anyString(), any()))
        .thenReturn(
            Map.of(
                "globalFeatureSystemNotificationEmailsEnabled", Map.of("value", true),
                "globalSmtpEnabled", Map.of("value", false)));

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class);
    verifyNoInteractions(inviteMailTransport);
  }

  @Test
  void send_Should_throwSmtpSendException_When_SettingsEndpointUnreachable() {
    when(restTemplate.getForObject(anyString(), any()))
        .thenThrow(new org.springframework.web.client.ResourceAccessException("down"));

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class);
    verifyNoInteractions(inviteMailTransport);
  }

  @Test
  void send_Should_fallBackToCredentialEndpoint_When_NoOperatorCredentialsConfigured() {
    when(restTemplate.getForObject(anyString(), any())).thenReturn(completeSettingsPayload());
    var credentials = new ApplicationSettingsSmtpCredentialsDTO();
    credentials.setGlobalSmtpUsername("endpoint-user");
    credentials.setGlobalSmtpPassword("endpoint-pass");
    when(applicationSettingsService.getGlobalSmtpCredentials())
        .thenReturn(Optional.of(credentials));
    when(inviteMailTransport.send(any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("to@example.org", Instant.now()));

    service("", "").send("to@example.org", "s", "b");

    verify(inviteMailTransport)
        .send(
            new InviteSmtpSettings(
                "smtp.example.org",
                587,
                false,
                "endpoint-user",
                "endpoint-pass",
                "noreply@example.org"),
            "to@example.org",
            "s",
            "b");
  }

  @Test
  void send_Should_throwSmtpSendException_When_NoCredentialsAnywhere() {
    when(restTemplate.getForObject(anyString(), any())).thenReturn(completeSettingsPayload());
    when(applicationSettingsService.getGlobalSmtpCredentials()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service("", "").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class);
    verifyNoInteractions(inviteMailTransport);
  }

  @Test
  void send_Should_propagateSmtpSendException_When_TransportFails() {
    when(restTemplate.getForObject(anyString(), any())).thenReturn(completeSettingsPayload());
    when(inviteMailTransport.send(any(), any(), any(), any()))
        .thenThrow(new SmtpSendException("handover failed", new RuntimeException("io")));

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("handover failed");
  }
}
