package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.notification.SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Transport;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OrisoEmailDispatcherTest {
  private final OrisoEmailDispatcher dispatcher = new OrisoEmailDispatcher();
  private final SupervisorAddedEmailSettings smtp =
      new SupervisorAddedEmailSettings(
          "smtp.example.org", 587, false, "test", "test", "sender@example.org", null);
  private final OrisoEmailRenderer.RenderedEmail email =
      new OrisoEmailRenderer.RenderedEmail(
          "Änderung bestätigt", "<p>Müller &amp; Team</p>", "Müller & Team");

  @Test
  void handsSmtpBothAlternativesWithUtf8AndCorrectRecipient() throws Exception {
    var sent = new AtomicReference<Message>();
    try (var transport = mockStatic(Transport.class)) {
      transport
          .when(() -> Transport.send(any(Message.class)))
          .thenAnswer(
              invocation -> {
                sent.set(invocation.getArgument(0));
                return null;
              });
      dispatcher.sendOrThrow(smtp, "recipient@example.org", email);
    }
    assertThat(sent.get().getSubject()).isEqualTo("Änderung bestätigt");
    assertThat(sent.get().getAllRecipients()[0].toString()).isEqualTo("recipient@example.org");
    Multipart mime = (Multipart) sent.get().getContent();
    assertThat(mime.getContentType()).startsWith("multipart/alternative");
    assertThat(mime.getCount()).isEqualTo(2);
    assertThat(mime.getBodyPart(0).getContent()).isEqualTo("Müller & Team");
    assertThat(mime.getBodyPart(1).getContent()).isEqualTo("<p>Müller &amp; Team</p>");
  }

  @Test
  void failsSynchronouslyAndCompatibilityCallerReceivesFalseOnSmtpFailure() {
    try (var transport = mockStatic(Transport.class)) {
      transport
          .when(() -> Transport.send(any(Message.class)))
          .thenThrow(new MessagingException("test failure"));
      assertThatThrownBy(() -> dispatcher.sendOrThrow(smtp, "recipient@example.org", email))
          .isInstanceOf(SmtpSendException.class);
      assertThat(dispatcher.send(smtp, "recipient@example.org", email)).isFalse();
    }
  }
}
