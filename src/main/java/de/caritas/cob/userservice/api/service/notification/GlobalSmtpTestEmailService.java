package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSmtpTestEmailDTO;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailMime;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.OrisoSmtpTransport;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalSmtpTestEmailService {

  private final @NonNull OrisoEmailRenderer emailRenderer;
  private final @NonNull OrisoEmailBrand emailBrand;
  private final @NonNull PlatformSmtpSettingsProvider platformSmtpSettings;

  // No fallback: an admin-triggered diagnostic mail that silently links into another
  // deployment is worse than a startup failure that says so.
  @Value("${system.notification.frontend.base-url}")
  private String appBaseUrl;

  /** Seam so tests can capture the rendered message without opening an SMTP socket. */
  @FunctionalInterface
  interface SmtpTransport {
    void send(MimeMessage message) throws Exception;
  }

  private SmtpTransport transport = OrisoSmtpTransport::send;

  public void sendTestEmail(GlobalSmtpTestEmailDTO dto) throws Exception {
    PlatformSmtpSettingsProvider.Settings configured;
    try {
      configured = platformSmtpSettings.requireConfigured();
    } catch (PlatformSmtpSettingsProvider.ConfigurationException exception) {
      throw new ConfigurationException(exception.getMessage());
    }
    jakarta.mail.Session session =
        OrisoSmtpTransport.session(
            configured.host(),
            configured.port(),
            configured.secure(),
            configured.username(),
            configured.password());

    var email = renderSmtpTest(configured);
    MimeMessage message = new MimeMessage(session);
    message.setFrom(new InternetAddress(configured.from()));
    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(dto.getRecipientEmail()));
    message.setSubject(email.subject(), "UTF-8");
    message.setContent(OrisoEmailMime.alternative(email));

    log.info("Sending global SMTP test email");
    transport.send(message);
  }

  /** Only this validated saved-settings error is safe to show to the platform administrator. */
  public static class ConfigurationException extends IllegalStateException {
    public ConfigurationException(String message) {
      super(message);
    }
  }

  private OrisoEmailRenderer.RenderedEmail renderSmtpTest(
      PlatformSmtpSettingsProvider.Settings configured) {
    try {
      emailBrand.platformName();
    } catch (IllegalStateException exception) {
      throw new ConfigurationException(
          "EMAIL_BRANDING_NAME is missing; configure the platform name before sending email");
    }
    Map<String, String> values = new LinkedHashMap<>(emailBrand.valuesForTenant(appBaseUrl, null));
    values.put("smtpHost", configured.host() + ":" + configured.port());
    values.put("smtpFrom", configured.from());
    values.put("sentAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    // A diagnostic that renders differently from production mail tests the
    // wrong thing, so this one goes through the same skeleton as everything
    // else.
    return emailRenderer.render("smtp-test", OrisoEmailRenderer.Tone.DE_FORMAL, values);
  }
}
