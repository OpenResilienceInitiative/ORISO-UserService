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

  /** #1006: a disabled toggle must be named, not folded into a generic "incomplete" failure. */
  @Test
  void send_Should_throwSmtpSendException_When_SmtpDisabled() {
    when(restTemplate.getForObject(anyString(), any()))
        .thenReturn(
            Map.of(
                "globalFeatureSystemNotificationEmailsEnabled", Map.of("value", true),
                "globalSmtpEnabled", Map.of("value", false)));

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("globalSmtpEnabled is disabled")
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            e ->
                assertThat(e.getCategory())
                    .isEqualTo(SmtpSendException.Category.SMTP_DISABLED_OR_INCOMPLETE));
    verifyNoInteractions(inviteMailTransport);
  }

  /** Review 3893223709: an absent toggle must not be reported as "disabled". */
  @Test
  void send_Should_reportToggleMissing_When_SmtpEnabledIsAbsent() {
    var payload = new java.util.HashMap<>(completeSettingsPayload());
    payload.remove("globalSmtpEnabled");
    when(restTemplate.getForObject(anyString(), any())).thenReturn(payload);

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("globalSmtpEnabled is missing or not a boolean")
        .hasMessageNotContaining("globalSmtpEnabled is disabled");
    verifyNoInteractions(inviteMailTransport);
  }

  /** Review 3893223709: a malformed toggle must not be reported as "disabled" either. */
  @Test
  void send_Should_reportToggleMalformed_When_SmtpEnabledIsNotABoolean() {
    var payload = new java.util.HashMap<>(completeSettingsPayload());
    payload.put("globalSmtpEnabled", Map.of("value", "banana"));
    when(restTemplate.getForObject(anyString(), any())).thenReturn(payload);

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("globalSmtpEnabled is missing or not a boolean")
        .hasMessageNotContaining("globalSmtpEnabled is disabled");
    verifyNoInteractions(inviteMailTransport);
  }

  /** Review 3893223709: same nullable-boolean rule for the system-emails feature toggle. */
  @Test
  void send_Should_reportToggleMissing_When_SystemEmailsToggleIsAbsent() {
    var payload = new java.util.HashMap<>(completeSettingsPayload());
    payload.remove("globalFeatureSystemNotificationEmailsEnabled");
    when(restTemplate.getForObject(anyString(), any())).thenReturn(payload);

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining(
            "globalFeatureSystemNotificationEmailsEnabled is missing or not a boolean");
    verifyNoInteractions(inviteMailTransport);
  }

  /**
   * Review 3893223709: 0, negative, out-of-range and fractional ports must fail here with a
   * field-specific message, not later in transport.
   */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("invalidPorts")
  void send_Should_rejectInvalidPort_When_PortIsNotAValidTcpPort(Object invalidPort) {
    var payload = new java.util.HashMap<>(completeSettingsPayload());
    payload.put("globalSmtpPort", Map.of("value", invalidPort));
    when(restTemplate.getForObject(anyString(), any())).thenReturn(payload);

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("globalSmtpPort is not a valid TCP port (1-65535)");
    verifyNoInteractions(inviteMailTransport);
  }

  static java.util.stream.Stream<Object> invalidPorts() {
    return java.util.stream.Stream.of(0, -25, 70000, 587.5d, "0", "-25", "70000");
  }

  /** Review 3893223709: an absent port keeps its distinct "missing" diagnosis. */
  @Test
  void send_Should_reportPortMissing_When_PortIsAbsent() {
    var payload = new java.util.HashMap<>(completeSettingsPayload());
    payload.remove("globalSmtpPort");
    when(restTemplate.getForObject(anyString(), any())).thenReturn(payload);

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("globalSmtpPort is missing or not a number");
    verifyNoInteractions(inviteMailTransport);
  }

  /** #1006: only the offending flag is reported when everything else is configured. */
  @Test
  void send_Should_nameTheDisabledFlag_When_SystemNotificationEmailsDisabled() {
    var payload = new java.util.HashMap<>(completeSettingsPayload());
    payload.put("globalFeatureSystemNotificationEmailsEnabled", Map.of("value", false));
    when(restTemplate.getForObject(anyString(), any())).thenReturn(payload);

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("globalFeatureSystemNotificationEmailsEnabled")
        .hasMessageNotContaining("globalSmtpHost");
    verifyNoInteractions(inviteMailTransport);
  }

  /** #1006: a missing connection field is named so the operator knows what to configure. */
  @Test
  void send_Should_nameTheMissingField_When_SmtpHostIsMissing() {
    var payload = new java.util.HashMap<>(completeSettingsPayload());
    payload.remove("globalSmtpHost");
    when(restTemplate.getForObject(anyString(), any())).thenReturn(payload);

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("globalSmtpHost")
        .hasMessageNotContaining("globalSmtpFrom");
    verifyNoInteractions(inviteMailTransport);
  }

  /** #1006: an unreachable settings endpoint is distinguished from bad configuration. */
  @Test
  void send_Should_throwSmtpSendException_When_SettingsEndpointUnreachable() {
    when(restTemplate.getForObject(anyString(), any()))
        .thenThrow(new org.springframework.web.client.ResourceAccessException("down"));

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("could not be reached")
        .hasMessageContaining("ResourceAccessException")
        .hasCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class)
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            e ->
                assertThat(e.getCategory())
                    .isEqualTo(SmtpSendException.Category.SMTP_SETTINGS_UNAVAILABLE));
    verifyNoInteractions(inviteMailTransport);
  }

  /**
   * Review 3893231991: an HTTP error means the endpoint WAS reached — the diagnosis must carry the
   * status code, not claim unreachability.
   */
  @Test
  void send_Should_reportHttpStatus_When_SettingsEndpointRespondsWithError() {
    when(restTemplate.getForObject(anyString(), any()))
        .thenThrow(
            org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("responded with HTTP 403")
        .hasMessageNotContaining("could not be reached")
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            e ->
                assertThat(e.getCategory())
                    .isEqualTo(SmtpSendException.Category.SMTP_SETTINGS_UNAVAILABLE));
    verifyNoInteractions(inviteMailTransport);
  }

  /** #1006: an empty payload is its own diagnosis, not a generic failure. */
  @Test
  void send_Should_sayPayloadEmpty_When_SettingsEndpointReturnsNothing() {
    when(restTemplate.getForObject(anyString(), any())).thenReturn(Map.of());

    assertThatThrownBy(() -> service("u", "p").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("empty");
    verifyNoInteractions(inviteMailTransport);
  }

  /** #1006: a missing base URL is a deployment defect and must be named as such. */
  @Test
  void send_Should_nameTheMissingProperty_When_ConsultingTypeServiceUrlNotConfigured() {
    var serviceWithoutUrl =
        new InviteMailDispatchService(
            restTemplate,
            applicationSettingsService,
            inviteMailTransport,
            emailBrandingResolver,
            renderer,
            "",
            "u",
            "p");

    assertThatThrownBy(() -> serviceWithoutUrl.send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("consulting.type.service.api.url");
    verifyNoInteractions(inviteMailTransport, restTemplate);
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

  /**
   * #1006: the credentials failure must tell the operator both ways out — set the deployment secret
   * (the supported configuration) or send with a platform-admin token. Never a generic "unavailable
   * or incomplete".
   */
  @Test
  void send_Should_throwSmtpSendException_When_NoCredentialsAnywhere() {
    when(restTemplate.getForObject(anyString(), any())).thenReturn(completeSettingsPayload());
    when(applicationSettingsService.getGlobalSmtpCredentials()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service("", "").send("to@example.org", "s", "b"))
        .isInstanceOf(SmtpSendException.class)
        .hasMessageContaining("SMTP_USER")
        .hasMessageContaining("SMTP_PASSWORD")
        .hasMessageContaining("platform-admin")
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            e ->
                assertThat(e.getCategory())
                    .isEqualTo(SmtpSendException.Category.SMTP_CREDENTIALS_MISSING));
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
        .hasMessageContaining("handover failed")
        // Review 3893231984: transport failures keep the default coarse category.
        .isInstanceOfSatisfying(
            SmtpSendException.class,
            e ->
                assertThat(e.getCategory())
                    .isEqualTo(SmtpSendException.Category.SMTP_TRANSPORT_FAILED));
  }
}
