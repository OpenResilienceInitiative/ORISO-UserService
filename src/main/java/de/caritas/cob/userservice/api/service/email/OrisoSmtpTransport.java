package de.caritas.cob.userservice.api.service.email;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import java.util.Properties;

/** Shared SMTP connection policy for every UserService mail sender. */
public final class OrisoSmtpTransport {

  private OrisoSmtpTransport() {}

  public static Session session(
      String host, int port, boolean implicitTls, String username, String password) {
    return Session.getInstance(
        properties(host, port, implicitTls),
        new Authenticator() {
          @Override
          protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(username, password);
          }
        });
  }

  public static Properties properties(String host, int port, boolean implicitTls) {
    Properties properties = new Properties();
    properties.put("mail.smtp.auth", "true");
    properties.put("mail.smtp.host", host);
    properties.put("mail.smtp.port", String.valueOf(port));
    properties.put("mail.smtp.connectiontimeout", "10000");
    properties.put("mail.smtp.timeout", "10000");
    properties.put("mail.smtp.writetimeout", "10000");
    properties.put("mail.smtp.ssl.checkserveridentity", "true");
    if (implicitTls) {
      properties.put("mail.smtp.ssl.enable", "true");
    } else {
      properties.put("mail.smtp.starttls.enable", "true");
      properties.put("mail.smtp.starttls.required", "true");
    }
    return properties;
  }

  public static void send(Message message) throws jakarta.mail.MessagingException {
    Transport.send(message);
  }
}
