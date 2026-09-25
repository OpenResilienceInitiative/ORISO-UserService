package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSmtpTestEmailDTO;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GlobalSmtpTestEmailServiceTest {
  @Mock private PlatformSmtpSettingsProvider platformSmtpSettings;
  @Mock private OrisoEmailRenderer emailRenderer;
  @Mock private OrisoEmailBrand emailBrand;
  @Mock private GlobalSmtpTestEmailService.SmtpTransport transport;

  private GlobalSmtpTestEmailService service;

  @BeforeEach
  void setUp() {
    service = new GlobalSmtpTestEmailService(emailRenderer, emailBrand, platformSmtpSettings);
    ReflectionTestUtils.setField(service, "appBaseUrl", "https://app.example.org");
    ReflectionTestUtils.setField(service, "transport", transport);
  }

  @Test
  void missingDeploymentPasswordStopsBeforeAnyTransportCall() {
    when(platformSmtpSettings.requireConfigured())
        .thenThrow(
            new IllegalStateException(
                "Platform SMTP is not configured: smtp.password (SMTP_PASSWORD)"));

    assertThatThrownBy(() -> service.sendTestEmail(request()))
        .isInstanceOf(GlobalSmtpTestEmailService.ConfigurationException.class)
        .hasMessageContaining("SMTP_PASSWORD");
    verifyNoInteractions(transport);
  }

  @Test
  void testMailUsesDeploymentServerAndDesignSystem() throws Exception {
    givenDeploymentSettings(true);
    givenRenderedMail();

    service.sendTestEmail(request());

    verify(emailRenderer)
        .render(
            eq("smtp-test"),
            eq(OrisoEmailRenderer.Tone.DE_FORMAL),
            org.mockito.ArgumentMatchers.argThat(
                values ->
                    "deployment-smtp.example.org:465".equals(values.get("smtpHost"))
                        && "noreply@deployment.example.org".equals(values.get("smtpFrom"))));
    MimeMessage sent = sentMessage();
    Properties session = sent.getSession().getProperties();
    assertThat(session.getProperty("mail.smtp.host")).isEqualTo("deployment-smtp.example.org");
    assertThat(session.getProperty("mail.smtp.port")).isEqualTo("465");
    assertThat(session.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
    assertThat(sent.getFrom()[0].toString()).isEqualTo("noreply@deployment.example.org");
    assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("to@example.com");
    assertThat(sent.getSubject()).isEqualTo("ORISO SMTP-Test");
    assertThat(sent.getContentType()).contains("multipart/alternative");
  }

  @Test
  void startTlsIsRequiredAndTimeoutsAreBounded() throws Exception {
    givenDeploymentSettings(false);
    givenRenderedMail();

    service.sendTestEmail(request());

    Properties session = sentMessage().getSession().getProperties();
    assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
    assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
    assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
    assertThat(session.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
    assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("10000");
    assertThat(session.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
  }

  @Test
  void legacyRequestThemeDoesNotChangePlatformMailBranding() throws Exception {
    givenDeploymentSettings(false);
    givenRenderedMail();
    GlobalSmtpTestEmailDTO dto = request();
    dto.setEmailThemeColor("#00ff00");

    service.sendTestEmail(dto);

    verify(emailBrand).values("https://app.example.org", null);
  }

  private void givenDeploymentSettings(boolean secure) {
    when(platformSmtpSettings.requireConfigured())
        .thenReturn(
            new PlatformSmtpSettingsProvider.Settings(
                "deployment-smtp.example.org",
                secure ? 465 : 587,
                secure,
                "deployment-user",
                "deployment-password",
                "noreply@deployment.example.org"));
  }

  private void givenRenderedMail() {
    when(emailBrand.values(eq("https://app.example.org"), any()))
        .thenReturn(Map.of("appUrl", "https://app.example.org"));
    when(emailRenderer.render(eq("smtp-test"), eq(OrisoEmailRenderer.Tone.DE_FORMAL), any()))
        .thenReturn(
            new OrisoEmailRenderer.RenderedEmail(
                "ORISO SMTP-Test", "<html>smtp test</html>", "smtp test"));
  }

  private MimeMessage sentMessage() throws Exception {
    ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
    verify(transport).send(captor.capture());
    MimeMessage sent = captor.getValue();
    sent.saveChanges();
    return sent;
  }

  private static GlobalSmtpTestEmailDTO request() {
    GlobalSmtpTestEmailDTO dto = new GlobalSmtpTestEmailDTO();
    dto.setRecipientEmail("to@example.com");
    return dto;
  }
}
