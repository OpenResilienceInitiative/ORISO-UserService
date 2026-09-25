package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSmtpTestEmailDTO;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailMime;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
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

  private SmtpTransport transport = Transport::send;

  public void sendTestEmail(GlobalSmtpTestEmailDTO dto) throws Exception {
    PlatformSmtpSettingsProvider.Settings configured;
    try {
      configured = platformSmtpSettings.requireConfigured();
    } catch (IllegalStateException exception) {
      throw new ConfigurationException(exception.getMessage());
    }
    Properties props = new Properties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.host", configured.host());
    props.put("mail.smtp.port", String.valueOf(configured.port()));
    // Never send the platform credentials in plaintext, and never hang an admin request.
    props.put("mail.smtp.ssl.checkserveridentity", "true");
    props.put("mail.smtp.connectiontimeout", "10000");
    props.put("mail.smtp.timeout", "10000");
    props.put("mail.smtp.writetimeout", "10000");
    if (configured.secure()) {
      props.put("mail.smtp.ssl.enable", "true");
    } else {
      props.put("mail.smtp.starttls.enable", "true");
      props.put("mail.smtp.starttls.required", "true");
    }

    jakarta.mail.Session session =
        jakarta.mail.Session.getInstance(
            props,
            new Authenticator() {
              @Override
              protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(configured.username(), configured.password());
              }
            });

    var email = renderSmtpTest(configured);
    MimeMessage message = new MimeMessage(session);
    message.setFrom(new InternetAddress(configured.from()));
    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(dto.getRecipientEmail()));
    message.setSubject(email.subject(), "UTF-8");
    message.setContent(OrisoEmailMime.alternative(email));

    log.info("Sending global SMTP test email");
    transport.send(message);
  }

  /** Only this validated deployment error is safe to show to the platform administrator. */
  public static class ConfigurationException extends IllegalStateException {
    public ConfigurationException(String message) {
      super(message);
    }
  }

  private OrisoEmailRenderer.RenderedEmail renderSmtpTest(
      PlatformSmtpSettingsProvider.Settings configured) {
    Map<String, String> values = new LinkedHashMap<>(emailBrand.values(appBaseUrl, null));
    values.put("smtpHost", configured.host() + ":" + configured.port());
    values.put("smtpFrom", configured.from());
    values.put("sentAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    // A diagnostic that renders differently from production mail tests the
    // wrong thing, so this one goes through the same skeleton as everything
    // else.
    return emailRenderer.render("smtp-test", OrisoEmailRenderer.Tone.DE_FORMAL, values);
  }
}
