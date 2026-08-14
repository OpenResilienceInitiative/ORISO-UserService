package de.caritas.cob.userservice.api.service.accountinvite.mail;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
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
    return send(settings, recipient, subject, htmlBody, null);
  }

  @Override
  public InviteMailSendReceipt send(
      InviteSmtpSettings settings,
      String recipient,
      String subject,
      String htmlBody,
      String plainTextBody) {
    try {
      Session session =
          Session.getInstance(
              buildSessionProperties(settings),
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
      if (isBlank(plainTextBody)) {
        message.setContent(htmlBody, "text/html; charset=UTF-8");
      } else {
        message.setContent(buildAlternativeContent(htmlBody, plainTextBody));
      }
      Transport.send(message);
      return new InviteMailSendReceipt(recipient, Instant.now());
    } catch (Exception exception) {
      throw new SmtpSendException("Account invite email could not be sent", exception);
    }
  }

  /**
   * Builds a {@code multipart/alternative} body (ORISO-UserService#914). The part order is part of
   * the contract: RFC 2046 declares the <em>last</em> alternative the richest, so the plain-text
   * part must come first for clients to prefer the branded HTML version.
   */
  static MimeMultipart buildAlternativeContent(String htmlBody, String plainTextBody)
      throws MessagingException {
    MimeBodyPart textPart = new MimeBodyPart();
    textPart.setText(plainTextBody, "UTF-8");
    // Set the header explicitly: MimeBodyPart only derives it during a later updateHeaders(), and
    // a part whose Content-Type is not spelled out is exactly how charset breakage happens.
    textPart.setHeader("Content-Type", "text/plain; charset=UTF-8");

    MimeBodyPart htmlPart = new MimeBodyPart();
    htmlPart.setContent(htmlBody == null ? "" : htmlBody, "text/html; charset=UTF-8");
    htmlPart.setHeader("Content-Type", "text/html; charset=UTF-8");

    MimeMultipart multipart = new MimeMultipart("alternative");
    multipart.addBodyPart(textPart);
    multipart.addBodyPart(htmlPart);
    return multipart;
  }

  /**
   * Builds the jakarta.mail session properties for the configured security mode. Hardening
   * (ORISO-Admin#569 follow-up): in STARTTLS mode ({@code secure() == false}) the upgrade is
   * mandatory — {@code mail.smtp.starttls.required} refuses servers (or men-in-the-middle) that do
   * not offer STARTTLS instead of silently sending credentials in plaintext. In both modes the
   * server certificate must match the configured host ({@code mail.smtp.ssl.checkserveridentity}).
   */
  static Properties buildSessionProperties(InviteSmtpSettings settings) {
    Properties properties = new Properties();
    properties.put("mail.smtp.auth", "true");
    properties.put("mail.smtp.host", settings.host());
    properties.put("mail.smtp.port", String.valueOf(settings.port()));
    properties.put("mail.smtp.connectiontimeout", "10000");
    properties.put("mail.smtp.timeout", "10000");
    properties.put("mail.smtp.writetimeout", "10000");
    properties.put("mail.smtp.ssl.checkserveridentity", "true");
    if (settings.secure()) {
      properties.put("mail.smtp.ssl.enable", "true");
    } else {
      properties.put("mail.smtp.starttls.enable", "true");
      properties.put("mail.smtp.starttls.required", "true");
    }
    return properties;
  }
}
