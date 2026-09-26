package de.caritas.cob.userservice.api.service.email;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
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
    try {
      sendOrThrow(smtp, recipient, email);
      return true;
    } catch (RuntimeException exception) {
      // Existing best-effort callers use a boolean; their send failure is still observable.
      return false;
    }
  }

  /** Preserves an ambiguous SMTP outcome for callers that must never replay blindly. */
  public void sendOrThrow(
      PlatformSmtpSettingsProvider.Settings smtp,
      String recipient,
      OrisoEmailRenderer.RenderedEmail email) {
    sendOrThrow(smtp, recipient, email, null);
  }

  /** The correlation header lets an operator match an uncertain reply to one stored claim. */
  public void sendOrThrow(
      PlatformSmtpSettingsProvider.Settings smtp,
      String recipient,
      OrisoEmailRenderer.RenderedEmail email,
      UUID correlationId) {
    try {
      MimeMessage message =
          new MimeMessage(
              OrisoSmtpTransport.session(
                  smtp.host(), smtp.port(), smtp.secure(), smtp.username(), smtp.password()));
      message.setFrom(new InternetAddress(smtp.from()));
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
      // UTF-8 rather than the platform default: these subjects carry umlauts.
      message.setSubject(email.subject(), "UTF-8");
      message.setContent(OrisoEmailMime.alternative(email));
      if (correlationId != null) {
        message.setHeader("X-ORISO-Delivery-ID", correlationId.toString());
      }
      OrisoSmtpTransport.send(message);
    } catch (Exception exception) {
      log.error("Platform mail send failed: {}", exception.getClass().getSimpleName());
      throw new IllegalStateException("Platform SMTP outcome is uncertain", exception);
    }
  }
}
