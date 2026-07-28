package de.caritas.cob.userservice.api.service.accountinvite.mail;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * Delivers invite mails synchronously via jakarta.mail. The receipt is created only after {@link
 * Transport#send(Message)} returned, i.e. after the SMTP server accepted the message.
 */
@Component
public class JakartaInviteMailTransport implements InviteMailTransport {

  @Override
  public InviteMailSendReceipt send(
      InviteSmtpSettings settings, String recipient, String subject, String htmlBody) {
    try {
      Properties properties = new Properties();
      properties.put("mail.smtp.auth", "true");
      properties.put("mail.smtp.host", settings.host());
      properties.put("mail.smtp.port", String.valueOf(settings.port()));
      properties.put("mail.smtp.connectiontimeout", "10000");
      properties.put("mail.smtp.timeout", "10000");
      properties.put("mail.smtp.writetimeout", "10000");
      if (settings.secure()) {
        properties.put("mail.smtp.ssl.enable", "true");
      } else {
        properties.put("mail.smtp.starttls.enable", "true");
      }

      Session session =
          Session.getInstance(
              properties,
              new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                  return new PasswordAuthentication(settings.username(), settings.password());
                }
              });
      Message message = new MimeMessage(session);
      message.setFrom(new InternetAddress(settings.from()));
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient, true));
      message.setSubject(subject);
      message.setContent(htmlBody, "text/html; charset=UTF-8");
      Transport.send(message);
      return new InviteMailSendReceipt(recipient, Instant.now());
    } catch (Exception exception) {
      throw new SmtpSendException("Account invite email could not be sent", exception);
    }
  }
}
