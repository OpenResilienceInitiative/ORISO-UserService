package de.caritas.cob.userservice.api.service.email;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.notification.SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sends a rendered ORISO mail over a tenant's SMTP settings.
 *
 * <p>The five direct-SMTP senders in this service each carry their own copy of this — the same
 * `Properties`, the same anonymous `Authenticator`, the same `Transport.send`. New senders use this
 * instead of adding a sixth. Moving the existing five over is a separate change: they work, and
 * rewriting them in the same commit that adds a mail would bury the new behaviour in a refactor.
 */
@Slf4j
@Component
public class OrisoEmailDispatcher {

  /**
   * @return whether the mail was handed to the SMTP server
   */
  public boolean send(
      SupervisorAddedEmailSettings smtp, String recipient, OrisoEmailRenderer.RenderedEmail email) {
    try {
      sendOrThrow(smtp, recipient, email);
      return true;
    } catch (SmtpSendException exception) {
      // Never log the exception itself. The cause chain here is the Jakarta Mail failure, and SMTP
      // rejection replies routinely embed the recipient address ("550 5.1.1 <a@b.example> user
      // unknown"). NotificationEmailService already refuses to retain these details for exactly
      // that reason; logging the throwable here would put advice-seeker addresses on disk anyway.
      // The type chain keeps auth failures, TLS failures and timeouts distinguishable.
      log.error(
          "ORISO email dispatch failed via SMTP host {}: {}", host(smtp), causeChain(exception));
      return false;
    }
  }

  /** Exception simple names only: no messages, no SMTP reply text, no addresses. */
  static String causeChain(Throwable throwable) {
    StringBuilder chain = new StringBuilder();
    Throwable current = throwable;
    int depth = 0;
    while (current != null && depth < 5) {
      if (depth > 0) {
        chain.append(" <- ");
      }
      chain.append(current.getClass().getSimpleName());
      if (current.getCause() == current) {
        break;
      }
      current = current.getCause();
      depth++;
    }
    return chain.toString();
  }

  /** The configured SMTP host is operator configuration, not personal data. */
  private static String host(SupervisorAddedEmailSettings smtp) {
    return smtp == null || smtp.getHost() == null ? "<unset>" : smtp.getHost();
  }

  /** Synchronous receipt boundary: returns only after SMTP accepts both MIME alternatives. */
  public void sendOrThrow(
      SupervisorAddedEmailSettings smtp, String recipient, OrisoEmailRenderer.RenderedEmail email) {
    try {
      MimeMessage message = new MimeMessage(sessionFor(smtp));
      message.setFrom(new InternetAddress(smtp.getFrom()));
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient, true));
      message.setSubject(email.subject(), "UTF-8");
      message.setContent(OrisoEmailMime.alternative(email));
      Transport.send(message);
    } catch (Exception exception) {
      throw new SmtpSendException("ORISO notification email could not be sent", exception);
    }
  }

  private Session sessionFor(SupervisorAddedEmailSettings smtp) {
    Properties props = new Properties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.ssl.checkserveridentity", "true");
    props.put("mail.smtp.host", smtp.getHost());
    props.put("mail.smtp.port", String.valueOf(smtp.getPort()));
    // Bounded, matching JakartaInviteMailTransport: an unresponsive SMTP host must not hang the
    // calling thread indefinitely — that thread usually comes from a bounded @Async pool.
    props.put("mail.smtp.connectiontimeout", "10000");
    props.put("mail.smtp.timeout", "10000");
    props.put("mail.smtp.writetimeout", "10000");
    if (smtp.isSecure()) {
      props.put("mail.smtp.ssl.enable", "true");
    } else {
      props.put("mail.smtp.starttls.enable", "true");
      // Without this, a STARTTLS-stripping man-in-the-middle just gets a plaintext session
      // instead of a failed connection.
      props.put("mail.smtp.starttls.required", "true");
    }

    return Session.getInstance(
        props,
        new Authenticator() {
          @Override
          protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(smtp.getUsername(), smtp.getPassword());
          }
        });
  }
}
