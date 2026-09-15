package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSmtpTestEmailDTO;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import jakarta.mail.internet.MimeMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GlobalSmtpTestEmailServiceTest {

  @Mock private ApplicationSettingsService applicationSettingsService;
  @Mock private OrisoEmailRenderer emailRenderer;
  @Mock private OrisoEmailBrand emailBrand;
  @Mock private GlobalSmtpTestEmailService.SmtpTransport transport;

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

  @Test
  void sendTestEmail_Should_RenderThroughTheDesignSystemAndHandToTheTransport() throws Exception {
    ReflectionTestUtils.setField(service, "appBaseUrl", "https://app.oriso.org");
    ReflectionTestUtils.setField(service, "transport", transport);

    ApplicationSettingsSmtpCredentialsDTO credentials = new ApplicationSettingsSmtpCredentialsDTO();
    credentials.setGlobalSmtpUsername("user");
    credentials.setGlobalSmtpPassword("pass");
    when(applicationSettingsService.getGlobalSmtpCredentials())
        .thenReturn(Optional.of(credentials));

    Map<String, String> brandValues = new HashMap<>();
    brandValues.put("appUrl", "https://app.oriso.org");
    when(emailBrand.values(eq("https://app.oriso.org"), any())).thenReturn(brandValues);
    when(emailRenderer.render(eq("smtp-test"), eq(OrisoEmailRenderer.Tone.DE_FORMAL), any()))
        .thenReturn(
            new OrisoEmailRenderer.RenderedEmail(
                "ORISO SMTP-Test", "<html>smtp test</html>", "smtp test"));

    GlobalSmtpTestEmailDTO dto = new GlobalSmtpTestEmailDTO();
    dto.setHost("smtp.invalid");
    dto.setPort(587);
    dto.setSecure(false);
    dto.setFrom("from@example.com");
    dto.setRecipientEmail("to@example.com");

    service.sendTestEmail(dto);

    verify(emailRenderer).render(eq("smtp-test"), eq(OrisoEmailRenderer.Tone.DE_FORMAL), any());
    ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
    verify(transport).send(captor.capture());
    MimeMessage sent = captor.getValue();
    sent.saveChanges();
    assertThat(sent.getSubject()).isEqualTo("ORISO SMTP-Test");
    assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("to@example.com");
    assertThat(sent.getContentType()).contains("multipart/alternative");
  }

  @Test
  void smtpDiagnosticUsesAdminCtaWithoutMovingFooterLinksUnderAdmin() {
    ReflectionTestUtils.setField(service, "appBaseUrl", "https://predev.oriso.org///");
    ReflectionTestUtils.setField(service, "emailRenderer", new OrisoEmailRenderer());
    Map<String, String> values = new HashMap<>();
    values.put("appUrl", "https://predev.oriso.org");
    values.put("settingsUrl", "https://predev.oriso.org/profile/settings");
    when(emailBrand.values(eq("https://predev.oriso.org///"), any())).thenReturn(values);
    GlobalSmtpTestEmailDTO dto = new GlobalSmtpTestEmailDTO();
    dto.setHost("smtp.invalid");
    dto.setPort(587);
    dto.setFrom("from@example.com");
    OrisoEmailRenderer.RenderedEmail rendered =
        ReflectionTestUtils.invokeMethod(service, "renderSmtpTest", dto);
    assertThat(rendered.html()).contains("href=\"https://predev.oriso.org/admin\"");
    assertThat(rendered.text()).contains("https://predev.oriso.org/admin");
    assertThat(rendered.html()).contains("href=\"https://predev.oriso.org/profile/settings\"");
    assertThat(rendered.html()).doesNotContain("/admin/profile", ".org//admin");
  }

  @Test
  void smtpBasePropertyUsesDeploymentAppBaseAndPreservesExplicitOverride() throws Exception {
    var environment = new org.springframework.core.env.StandardEnvironment();
    environment.getPropertySources().remove("systemEnvironment");
    environment.getPropertySources().remove("systemProperties");
    var deployment = new HashMap<String, Object>();
    deployment.put("APP_BASE_URL", "https://predev.oriso.org");
    environment
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.SystemEnvironmentPropertySource(
                "deployment", deployment));
    var properties =
        org.springframework.core.io.support.PropertiesLoaderUtils.loadProperties(
            new org.springframework.core.io.ClassPathResource("application.properties"));
    environment
        .getPropertySources()
        .addLast(
            new org.springframework.core.env.PropertiesPropertySource("application", properties));
    assertThat(environment.getProperty("system.notification.frontend.base-url"))
        .isEqualTo("https://predev.oriso.org");
    deployment.put("SYSTEM_NOTIFICATION_FRONTEND_BASE_URL", "https://explicit.example.org");
    assertThat(environment.getProperty("system.notification.frontend.base-url"))
        .isEqualTo("https://explicit.example.org");
  }
}
