package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSmtpTestEmailDTO;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GlobalSmtpTestEmailService {

  public void sendTestEmail(GlobalSmtpTestEmailDTO dto) throws Exception {
    Properties props = new Properties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.host", dto.getHost());
    props.put("mail.smtp.port", String.valueOf(dto.getPort()));
    if (Boolean.TRUE.equals(dto.getSecure())) {
      props.put("mail.smtp.ssl.enable", "true");
    } else {
      props.put("mail.smtp.starttls.enable", "true");
    }

    javax.mail.Session session =
        javax.mail.Session.getInstance(
            props,
            new Authenticator() {
              @Override
              protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(dto.getUsername(), dto.getPassword());
              }
            });

    Message message = new MimeMessage(session);
    message.setFrom(new InternetAddress(dto.getFrom()));
    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(dto.getRecipientEmail()));
    message.setSubject("ORISO Global SMTP Test");
    message.setContent(buildHtml(dto), "text/html; charset=UTF-8");

    log.info("Sending global SMTP test email to {}", dto.getRecipientEmail());
    Transport.send(message);
  }

  private String buildHtml(GlobalSmtpTestEmailDTO dto) {
    String color =
        isNotBlank(dto.getEmailThemeColor()) && dto.getEmailThemeColor().matches("^#([A-Fa-f0-9]{6})$")
            ? dto.getEmailThemeColor()
            : "#0f3b8f";
    String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

    return "<!doctype html><html><body style=\"margin:0;padding:0;background:#f6f7fb;font-family:Arial,sans-serif;\">"
        + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"padding:24px 0;\">"
        + "<tr><td align=\"center\">"
        + "<table role=\"presentation\" width=\"620\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:620px;background:#ffffff;border:1px solid #e5e7eb;border-radius:12px;overflow:hidden;\">"
        + "<tr><td style=\"background:"
        + color
        + ";padding:18px 24px;color:#ffffff;font-size:20px;font-weight:700;\">ORISO</td></tr>"
        + "<tr><td style=\"padding:28px 24px 8px 24px;color:#111827;font-size:24px;line-height:32px;font-weight:700;\">SMTP test successful</td></tr>"
        + "<tr><td style=\"padding:0 24px 18px 24px;color:#374151;font-size:16px;line-height:24px;\">This is a test email sent from global superadmin SMTP settings.</td></tr>"
        + "<tr><td style=\"padding:0 24px 24px 24px;color:#6b7280;font-size:14px;line-height:22px;\">Sent at: "
        + now
        + "</td></tr>"
        + "</table></td></tr></table></body></html>";
  }
}


