package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class OrisoEmailDispatcherCorrelationTest {
  @Test
  void replyMailCarriesTheStoredDeliveryIdentifier() throws Exception {
    var smtp =
        new PlatformSmtpSettingsProvider.Settings(
            "smtp.example.org", 587, false, "account", "secret", "sender@example.org");
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
}
