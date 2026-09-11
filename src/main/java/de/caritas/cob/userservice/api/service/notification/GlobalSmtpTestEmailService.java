package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSmtpTestEmailDTO;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
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

  private final @NonNull ApplicationSettingsService applicationSettingsService;
  private final @NonNull OrisoEmailRenderer emailRenderer;
  private final @NonNull OrisoEmailBrand emailBrand;

  // No fallback: an admin-triggered diagnostic mail that silently links into the wrong
  // deployment's app.oriso.org is worse than a startup failure that says so.
  @Value("${system.notification.frontend.base-url}")
  private String appBaseUrl;

  /** Seam so tests can capture the rendered message without opening an SMTP socket. */
  @FunctionalInterface
  interface SmtpTransport {
    void send(MimeMessage message) throws Exception;
  }

  private SmtpTransport transport = Transport::send;

  public void sendTestEmail(GlobalSmtpTestEmailDTO dto) throws Exception {
    var credentials =
        applicationSettingsService
            .getGlobalSmtpCredentials()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "SMTP credentials are not configured in application settings."));
    Properties props = new Properties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.host", dto.getHost());
    props.put("mail.smtp.port", String.valueOf(dto.getPort()));
    if (Boolean.TRUE.equals(dto.getSecure())) {
      props.put("mail.smtp.ssl.enable", "true");
    } else {
      props.put("mail.smtp.starttls.enable", "true");
    }

    jakarta.mail.Session session =
        jakarta.mail.Session.getInstance(
            props,
            new Authenticator() {
              @Override
              protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                    credentials.getGlobalSmtpUsername(), credentials.getGlobalSmtpPassword());
              }
            });

    var email = renderSmtpTest(dto);
    MimeMessage message = new MimeMessage(session);
    message.setFrom(new InternetAddress(dto.getFrom()));
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

  private OrisoEmailRenderer.RenderedEmail renderSmtpTest(GlobalSmtpTestEmailDTO dto) {
    Map<String, String> values =
        new LinkedHashMap<>(emailBrand.values(appBaseUrl, dto.getEmailThemeColor()));
    values.put("smtpHost", dto.getHost() + ":" + dto.getPort());
    values.put("smtpFrom", dto.getFrom());
    values.put("sentAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    // A diagnostic that renders differently from production mail tests the
    // wrong thing, so this one goes through the same skeleton as everything
    // else.
    return emailRenderer.render("smtp-test", OrisoEmailRenderer.Tone.DE_FORMAL, values);
  }
}
