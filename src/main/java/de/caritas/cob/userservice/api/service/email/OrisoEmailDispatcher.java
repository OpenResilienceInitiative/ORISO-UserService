package de.caritas.cob.userservice.api.service.email;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Sends a rendered ORISO mail using the shared SMTP connection policy. */
@Slf4j
@Component
public class OrisoEmailDispatcher {

  /**
   * @return whether the mail was handed to the SMTP server
   */
  public boolean send(
      PlatformSmtpSettingsProvider.Settings smtp,
      String recipient,
      OrisoEmailRenderer.RenderedEmail email) {
    return send(smtp, recipient, email, null);
  }

  /** Sends with an opaque, stable receipt key when the caller has a durable outbox row. */
  public boolean send(
      PlatformSmtpSettingsProvider.Settings smtp,
      String recipient,
      OrisoEmailRenderer.RenderedEmail email,
      String correlationId) {
    try {
      MimeMessage message =
          new MimeMessage(
              OrisoSmtpTransport.session(
                  smtp.host(), smtp.port(), smtp.secure(), smtp.username(), smtp.password()));
      message.setFrom(new InternetAddress(smtp.from()));
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
      // UTF-8 rather than the platform default: these subjects carry umlauts.
      message.setSubject(email.subject(), "UTF-8");
      if (correlationId != null) {
        message.setHeader("X-ORISO-Correlation-ID", requireCorrelationId(correlationId));
      }
      message.setContent(OrisoEmailMime.alternative(email));
      OrisoSmtpTransport.send(message);
      return true;
    } catch (Exception exception) {
      // A mail that cannot be sent must not fail the operation that triggered
      // it — a registration that rolls back because the welcome mail bounced
      // would be a far worse outcome than a missing mail.
      log.error("Platform mail send failed: {}", exception.getClass().getSimpleName());
      return false;
    }
  }

  private static String requireCorrelationId(String correlationId) {
    String canonical = java.util.UUID.fromString(correlationId).toString();
    if (!canonical.equals(correlationId)) {
      throw new IllegalArgumentException("Mail correlation ID must be a canonical UUID");
    }
    return canonical;
  }
}
