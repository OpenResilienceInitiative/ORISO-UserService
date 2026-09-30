package de.caritas.cob.userservice.api.service.email;

import de.caritas.cob.userservice.api.service.notification.SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings;
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
      SupervisorAddedEmailSettings smtp, String recipient, OrisoEmailRenderer.RenderedEmail email) {
    try {
      MimeMessage message =
          new MimeMessage(
              OrisoSmtpTransport.session(
                  smtp.getHost(),
                  smtp.getPort(),
                  smtp.isSecure(),
                  smtp.getUsername(),
                  smtp.getPassword()));
      message.setFrom(new InternetAddress(smtp.getFrom()));
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
      // UTF-8 rather than the platform default: these subjects carry umlauts.
      message.setSubject(email.subject(), "UTF-8");
      message.setContent(OrisoEmailMime.alternative(email));
      OrisoSmtpTransport.send(message);
      return true;
    } catch (Exception exception) {
      // A mail that cannot be sent must not fail the operation that triggered
      // it — a registration that rolls back because the welcome mail bounced
      // would be a far worse outcome than a missing mail.
      log.error(
          "Failed to send '{}' to a recipient of tenant SMTP host {}",
          email.subject(),
          smtp.getHost(),
          exception);
      return false;
    }
  }
}
