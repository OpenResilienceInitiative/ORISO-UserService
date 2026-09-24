package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSmtpTestEmailDTO;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteSmtpSettings;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailMime;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
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
  private final @NonNull InviteMailDispatchService storedSmtpSettings;

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
    InviteSmtpSettings stored = storedSettings();
    Properties props = new Properties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.host", stored.host());
    props.put("mail.smtp.port", String.valueOf(stored.port()));
    // Never send the platform credentials in plaintext, and never hang an admin request.
    props.put("mail.smtp.ssl.checkserveridentity", "true");
    props.put("mail.smtp.connectiontimeout", "10000");
    props.put("mail.smtp.timeout", "10000");
    props.put("mail.smtp.writetimeout", "10000");
    if (stored.secure()) {
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
                return new PasswordAuthentication(stored.username(), stored.password());
              }
            });

    var email = renderSmtpTest(dto, stored);
    MimeMessage message = new MimeMessage(session);
    message.setFrom(new InternetAddress(stored.from()));
    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(dto.getRecipientEmail()));
    message.setSubject(email.subject(), "UTF-8");
    message.setContent(OrisoEmailMime.alternative(email));

    log.info("Sending global SMTP test email to {}", mask(dto.getRecipientEmail()));
    transport.send(message);
  }

  /**
   * {@code a***@example.com} — enough to confirm the right inbox in a log line, not the address.
   */
  private static String mask(String email) {
    int at = email == null ? -1 : email.indexOf('@');
    if (at <= 0) {
      return "***";
    }
    return email.charAt(0) + "***" + email.substring(at);
  }

  private InviteSmtpSettings storedSettings() {
    try {
      return storedSmtpSettings.storedPlatformSmtpSettings();
    } catch (SmtpSendException exception) {
      // The controller shows IllegalStateException messages to the admin; keep them short.
      log.warn("Stored SMTP settings unusable for the test mail: {}", exception.getMessage());
      throw new IllegalStateException(adminMessage(exception.getCategory()), exception);
    }
  }

  private static String adminMessage(SmtpSendException.Category category) {
    return switch (category) {
      case SMTP_CREDENTIALS_MISSING ->
          "SMTP credentials are not configured in application settings.";
      case SMTP_DISABLED_OR_INCOMPLETE ->
          "The stored SMTP settings are incomplete. Save host, port, security and sender first.";
      default -> "The stored SMTP settings could not be loaded.";
    };
  }

  private OrisoEmailRenderer.RenderedEmail renderSmtpTest(
      GlobalSmtpTestEmailDTO dto, InviteSmtpSettings stored) {
    Map<String, String> values =
        new LinkedHashMap<>(emailBrand.values(appBaseUrl, dto.getEmailThemeColor()));
    values.put("smtpHost", stored.host() + ":" + stored.port());
    values.put("smtpFrom", stored.from());
    values.put("sentAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    // A diagnostic that renders differently from production mail tests the
    // wrong thing, so this one goes through the same skeleton as everything
    // else.
    return emailRenderer.render("smtp-test", OrisoEmailRenderer.Tone.DE_FORMAL, values);
  }
}
