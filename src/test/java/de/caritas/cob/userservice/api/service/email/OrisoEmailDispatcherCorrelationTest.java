package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class OrisoEmailDispatcherCorrelationTest {
  @Test
  void replyMailCarriesTheStoredDeliveryIdentifier() throws Exception {
    var smtp =
        new PlatformSmtpSettingsProvider.Settings(
            "smtp.example.org", 587, false, "account", "secret", "sender@example.org", "#123456");
    var correlation = UUID.fromString("ab2e5141-2f26-456a-9e46-0ff642918115");
    var email = new OrisoEmailRenderer.RenderedEmail("Subject", "<p>Body</p>", "Body");

    try (MockedStatic<OrisoSmtpTransport> transport =
        Mockito.mockStatic(OrisoSmtpTransport.class, CALLS_REAL_METHODS)) {
      transport.when(() -> OrisoSmtpTransport.send(any(Message.class))).thenAnswer(call -> null);

      new OrisoEmailDispatcher().sendOrThrow(smtp, "recipient@example.org", email, correlation);

      var sent = ArgumentCaptor.forClass(Message.class);
      transport.verify(() -> OrisoSmtpTransport.send(sent.capture()));
      var message = (MimeMessage) sent.getValue();
      message.saveChanges();
      assertThat(message.getHeader("X-ORISO-Delivery-ID")).containsExactly(correlation.toString());
    }
  }

  @ParameterizedTest
  @EnumSource(OrisoEmailRenderer.Tone.class)
  void consultantMessageMailRendersSevenNeutralMultipartVariants(OrisoEmailRenderer.Tone tone)
      throws Exception {
    var renderer = new OrisoEmailRenderer(true);
    var values =
        Map.ofEntries(
            Map.entry("platformName", "Community Hub"),
            Map.entry("orgName", "Community Hub Operator"),
            Map.entry("orgAddress", ""),
            Map.entry("contactLine", ""),
            Map.entry("logoUrl", "https://tenant.example.net/logo.png"),
            Map.entry("primaryColor", "#112233"),
            Map.entry("accentColor", "#112233"),
            Map.entry(
                "messageUrl",
                "https://tenant.example.net/sessions/consultant/sessionView/%21room/42"),
            Map.entry("settingsUrl", "https://tenant.example.net/profile/einstellungen"),
            Map.entry("privacyUrl", "https://tenant.example.net/datenschutz"),
            Map.entry("imprintUrl", "https://tenant.example.net/impressum"),
            Map.entry("unsubscribeUrl", "https://tenant.example.net/profile/einstellungen/email"));
    var rendered = renderer.render("neue-nachricht-beratung", tone, values);
    var smtp =
        new PlatformSmtpSettingsProvider.Settings(
            "smtp.example.org", 587, false, "account", "secret", "sender@example.org", "#112233");

    try (MockedStatic<OrisoSmtpTransport> transport =
        Mockito.mockStatic(OrisoSmtpTransport.class, CALLS_REAL_METHODS)) {
      transport.when(() -> OrisoSmtpTransport.send(any(Message.class))).thenAnswer(call -> null);

      new OrisoEmailDispatcher()
          .sendOrThrow(smtp, "consultant@example.org", rendered, UUID.randomUUID());

      var sent = ArgumentCaptor.forClass(Message.class);
      transport.verify(() -> OrisoSmtpTransport.send(sent.capture()));
      var mime = (MimeMessage) sent.getValue();
      var parts = (MimeMultipart) mime.getContent();
      assertThat(parts.getCount()).isEqualTo(2);
      String plain = parts.getBodyPart(0).getContent().toString();
      String html = parts.getBodyPart(1).getContent().toString();
      assertThat(mime.getSubject())
          .isEqualTo(
              renderer
                  .subjectOf("neue-nachricht-beratung", tone)
                  .replace("{{platformName}}", "Community Hub"));
      assertThat(html).contains(renderer.preheaderOf("neue-nachricht-beratung", tone));
      assertThat(plain)
          .contains("https://tenant.example.net/sessions/consultant/sessionView/%21room/42");
      assertThat(html).contains("https://tenant.example.net/logo.png", "#112233");
      assertThat(mime.getSubject() + plain + html)
          .doesNotContain(
              "PRIVATE_PERSON_MARKER", "PRIVATE_CASE_MARKER", "PRIVATE_CENTRE_MARKER", "{{");
    }
  }
  @Test
  void durableCorrelationIsWrittenIntoThePlatformMimeHeader() throws Exception {
    var smtp =
        new PlatformSmtpSettingsProvider.Settings(
            "smtp.example.org", 587, false, "account", "secret", "sender@example.org");
    var email = new OrisoEmailRenderer.RenderedEmail("Date", "<p>Date</p>", "Date");
    UUID correlation = UUID.fromString("f7e8bbfe-7ca9-4e8e-8c55-54575ceca5a9");
    var sent = new AtomicReference<MimeMessage>();
    try (var transport = Mockito.mockStatic(OrisoSmtpTransport.class)) {
      transport
          .when(
              () -> OrisoSmtpTransport.session("smtp.example.org", 587, false, "account", "secret"))
          .thenReturn(Session.getInstance(new Properties()));
      transport
          .when(() -> OrisoSmtpTransport.send(any(Message.class)))
          .thenAnswer(
              invocation -> {
                sent.set((MimeMessage) invocation.getArgument(0));
                return null;
              });

      boolean accepted =
          new OrisoEmailDispatcher().send(smtp, "recipient@example.org", email, correlation);

      assertThat(accepted).isTrue();
      assertThat(sent.get().getHeader("X-ORISO-Delivery-ID", null)).isEqualTo(correlation.toString());
    }
  }
}
