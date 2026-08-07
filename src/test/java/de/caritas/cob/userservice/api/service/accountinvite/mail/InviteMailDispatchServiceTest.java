package de.caritas.cob.userservice.api.service.accountinvite.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmailLayoutRenderer;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.layout.EmailContentSanitizer;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

/**
 * Strict-send contract (TEN-INV-U6, #890): either the transport confirms the handover and a receipt
 * is returned, or an {@link SmtpSendException} propagates — including when the global SMTP
 * configuration is unavailable. There is no silent-failure path.
 *
 * <p>Since #914 the dispatcher is also the single place applying the branded layout, so the tests
 * additionally pin that authored content is wrapped, never transmitted raw.
 */
@ExtendWith(MockitoExtension.class)
class InviteMailDispatchServiceTest {

  @Mock private RestTemplate restTemplate;
  @Mock private ApplicationSettingsService applicationSettingsService;
  @Mock private InviteMailTransport inviteMailTransport;
  @Mock private EmailBrandingResolver emailBrandingResolver;

  private final BrandedEmailLayoutRenderer renderer =
      new BrandedEmailLayoutRenderer(new EmailContentSanitizer());

  private InviteMailDispatchService service(String smtpUser, String smtpPassword) {
    return new InviteMailDispatchService(
        restTemplate,
        applicationSettingsService,
        inviteMailTransport,
        emailBrandingResolver,
        renderer,
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
        "globalSmtpFrom", Map.of("value", "noreply@example.org"),
        "globalSmtpEmailThemeColor", Map.of("value", "#f8e71c"));
  }

  private static InviteSmtpSettings expectedSmtpSettings(String user, String password) {
    return new InviteSmtpSettings(
        "smtp.example.org", 587, false, user, password, "noreply@example.org");
  }

  private void givenNeutralBranding() {
    when(emailBrandingResolver.resolve(any())).thenReturn(EmailBranding.neutral());
  }

  @Test
  void send_Should_returnTransportReceipt_When_SettingsCompleteAndTransportConfirms() {
    when(restTemplate.getForObject(anyString(), any())).thenReturn(completeSettingsPayload());
    givenNeutralBranding();
    InviteMailSendReceipt receipt = new InviteMailSendReceipt("to@example.org", Instant.now());
    when(inviteMailTransport.send(any(), any(), any(), any(), any())).thenReturn(receipt);

    var result = service("smtp-user", "smtp-pass").send("to@example.org", "subject", "body");

    assertThat(result).isSameAs(receipt);
    verify(inviteMailTransport)
        .send(
            eq(expectedSmtpSettings("smtp-user", "smtp-pass")),
            eq("to@example.org"),
            eq("subject"),
            anyString(),
            anyString());
  }

  /** #914: the authored body must reach the recipient inside the branded frame, never raw. */
  @Test
  void send_Should_wrapAuthoredBodyInBrandedLayoutAndAddPlainTextPart() {
    when(restTemplate.getForObject(anyString(), any())).thenReturn(completeSettingsPayload());
    givenNeutralBranding();
    when(inviteMailTransport.send(any(), any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("to@example.org", Instant.now()));

    service("u", "p")
        .send(
            "to@example.org",
            "Ihre Einladung",
            "Hallo Ada, bitte bestaetigen Sie Ihr Konto.",
            "https://app.oriso.org/account-invite/tok",
            null,
            "de");

    ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(inviteMailTransport)
        .send(any(), eq("to@example.org"), eq("Ihre Einladung"), html.capture(), text.capture());

    assertThat(html.getValue())
        .startsWith("<!doctype html>")
        .contains("Hallo Ada, bitte bestaetigen Sie Ihr Konto.")
        .contains("https://app.oriso.org/account-invite/tok");
    assertThat(text.getValue())
        .doesNotContain("<table")
        .contains("Hallo Ada, bitte bestaetigen Sie Ihr Konto.")
        .contains("https://app.oriso.org/account-invite/tok");
  }

  /**
   * The mail palette follows the product colour rule only (#914, final decision): branding is
   * resolved from the tenant, and the SMTP "E-Mail Designfarbe" is not part of the chain — even
   * though it travels in the very settings payload the SMTP connection comes from.
   */
  @Test
  void send_Should_resolveBrandingFromTheTenantAndIgnoreTheSmtpThemeColor() {
    when(restTemplate.getForObject(anyString(), any())).thenReturn(completeSettingsPayload());
    givenNeutralBranding();
    when(inviteMailTransport.send(any(), any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("to@example.org", Instant.now()));

    service("u", "p").send("to@example.org", "s", "b", null, 42L, null);

    verify(emailBrandingResolver).resolve(42L);
    ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
    verify(inviteMailTransport).send(any(), any(), any(), html.capture(), anyString());
    assertThat(html.getValue())
        .as("the SMTP theme colour #f8e71c must not reach the mail")
        .doesNotContain("f8e71c");
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
    givenNeutralBranding();
    var credentials = new ApplicationSettingsSmtpCredentialsDTO();
    credentials.setGlobalSmtpUsername("endpoint-user");
    credentials.setGlobalSmtpPassword("endpoint-pass");
    when(applicationSettingsService.getGlobalSmtpCredentials())
        .thenReturn(Optional.of(credentials));
    when(inviteMailTransport.send(any(), any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("to@example.org", Instant.now()));

    service("", "").send("to@example.org", "s", "b");

    verify(inviteMailTransport)
        .send(
            eq(expectedSmtpSettings("endpoint-user", "endpoint-pass")),
            eq("to@example.org"),
            eq("s"),
            anyString(),
            anyString());
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
    givenNeutralBranding();
    when(inviteMailTransport.send(any(), any(), any(), any(), any()))
        .thenThrow(new SmtpSendException("handover failed", new RuntimeException("io")));

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("handover failed");
  }
}
