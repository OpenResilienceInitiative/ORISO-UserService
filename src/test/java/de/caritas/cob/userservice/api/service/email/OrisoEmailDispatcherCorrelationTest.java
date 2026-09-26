package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OrisoEmailDispatcherCorrelationTest {
  @Test
  void durableCorrelationIsWrittenIntoThePlatformMimeHeader() throws Exception {
    var smtp =
        new PlatformSmtpSettingsProvider.Settings(
            "smtp.example.org", 587, false, "account", "secret", "sender@example.org");
    var email = new OrisoEmailRenderer.RenderedEmail("Date", "<p>Date</p>", "Date");
    String correlation = "f7e8bbfe-7ca9-4e8e-8c55-54575ceca5a9";
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
      assertThat(sent.get().getHeader("X-ORISO-Correlation-ID", null)).isEqualTo(correlation);
    }
  }
}
