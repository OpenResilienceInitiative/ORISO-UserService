package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSmtpTestEmailDTO;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteFrameMailRenderer;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailTransport;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import jakarta.mail.internet.MimeMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class GlobalSmtpTestEmailServiceTest {

  private static final String STORED_HOST = "smtp.stored.example.org";

  @Mock private RestTemplate restTemplate;
  @Mock private ApplicationSettingsService applicationSettingsService;
  @Mock private OrisoEmailRenderer emailRenderer;
  @Mock private OrisoEmailBrand emailBrand;
  @Mock private GlobalSmtpTestEmailService.SmtpTransport transport;

  private GlobalSmtpTestEmailService service;

  @BeforeEach
  void setUp() {
    // The stored platform settings come from the same source real mails use: the
    // ConsultingTypeService /settings payload plus the stored credentials.
    var storedSettings =
        new InviteMailDispatchService(
            restTemplate,
            applicationSettingsService,
            mock(InviteMailTransport.class),
            mock(InviteFrameMailRenderer.class),
            "http://consultingtypeservice:8080/service",
            "",
            "");
    service = new GlobalSmtpTestEmailService(emailRenderer, emailBrand, storedSettings);
    ReflectionTestUtils.setField(service, "appBaseUrl", "https://app.example.org");
    ReflectionTestUtils.setField(service, "transport", transport);
  }

  @Test
  void sendTestEmail_Should_ThrowIllegalState_When_SmtpCredentialsNotConfigured() {
    givenStoredSettings(storedPayload(false));
    when(applicationSettingsService.getGlobalSmtpCredentials()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.sendTestEmail(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMTP credentials");
  }

  @Test
  void sendTestEmail_Should_RenderThroughTheDesignSystemAndHandToTheTransport() throws Exception {
    givenStoredSettings(storedPayload(false));
    givenStoredCredentials();
    givenRenderedMail();

    service.sendTestEmail(request());

    verify(emailRenderer).render(eq("smtp-test"), eq(OrisoEmailRenderer.Tone.DE_FORMAL), any());
    MimeMessage sent = sentMessage();
    assertThat(sent.getSubject()).isEqualTo("ORISO SMTP-Test");
    assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("to@example.com");
    assertThat(sent.getContentType()).contains("multipart/alternative");
  }

  @Test
  void sendTestEmail_Should_ConnectToTheStoredPlatformServer() throws Exception {
    givenStoredSettings(storedPayload(true));
    givenStoredCredentials();
    givenRenderedMail();

    service.sendTestEmail(request());

    MimeMessage sent = sentMessage();
    Properties session = sent.getSession().getProperties();
    assertThat(session.getProperty("mail.smtp.host")).isEqualTo(STORED_HOST);
    assertThat(session.getProperty("mail.smtp.port")).isEqualTo("465");
    assertThat(session.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
    assertThat(sent.getFrom()[0].toString()).isEqualTo("noreply@stored.example.org");
  }

  @Test
  void sendTestEmail_Should_StillSend_When_PlatformMailsAreSwitchedOff() throws Exception {
    // Operators test the stored server before they switch system mails on.
    var switchedOff = new HashMap<>(storedPayload(false));
    switchedOff.put("globalFeatureSystemNotificationEmailsEnabled", Map.of("value", false));
    switchedOff.put("globalSmtpEnabled", Map.of("value", false));
    givenStoredSettings(switchedOff);
    givenStoredCredentials();
    givenRenderedMail();

    service.sendTestEmail(request());

    assertThat(sentMessage().getSession().getProperty("mail.smtp.host")).isEqualTo(STORED_HOST);
  }

  @Test
  void sendTestEmail_Should_RequireStartTlsAndBoundTheConnection_When_StoredServerIsNotSsl()
      throws Exception {
    givenStoredSettings(storedPayload(false));
    givenStoredCredentials();
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

  private void givenStoredSettings(Map<String, Object> payload) {
    when(restTemplate.getForObject(anyString(), any())).thenReturn(payload);
  }

  private static Map<String, Object> storedPayload(boolean secure) {
    return Map.of(
        "globalFeatureSystemNotificationEmailsEnabled", Map.of("value", true),
        "globalSmtpEnabled", Map.of("value", true),
        "globalSmtpHost", Map.of("value", STORED_HOST),
        "globalSmtpPort", Map.of("value", secure ? "465" : "587"),
        "globalSmtpSecure", Map.of("value", secure),
        "globalSmtpFrom", Map.of("value", "noreply@stored.example.org"));
  }

  private void givenStoredCredentials() {
    var credentials = new ApplicationSettingsSmtpCredentialsDTO();
    credentials.setGlobalSmtpUsername("user");
    credentials.setGlobalSmtpPassword("pass");
    when(applicationSettingsService.getGlobalSmtpCredentials())
        .thenReturn(Optional.of(credentials));
  }

  private void givenRenderedMail() {
    Map<String, String> brandValues = new HashMap<>();
    brandValues.put("appUrl", "https://app.example.org");
    lenient().when(emailBrand.values(eq("https://app.example.org"), any())).thenReturn(brandValues);
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
