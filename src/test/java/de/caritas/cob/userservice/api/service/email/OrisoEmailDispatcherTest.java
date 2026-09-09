package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.notification.SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
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

  @Test
  void neverLogsTheRecipientAddressCarriedInAnSmtpRejectionReply() {
    // A real provider reply embeds the address it rejected. Logging the throwable would put it
    // on disk; NotificationEmailService refuses to retain these details for the same reason.
    String reply = "550 5.1.1 <advice.seeker@example.org> user unknown";
    try (var transport = mockStatic(Transport.class);
        var logs = LogbackCaptor.forClass(OrisoEmailDispatcher.class)) {
      transport
          .when(() -> Transport.send(any(Message.class)))
          .thenThrow(new MessagingException(reply));

      assertThat(dispatcher.send(smtp, "advice.seeker@example.org", email)).isFalse();

      assertThat(logs.count(Level.ERROR)).isEqualTo(1);
      assertThat(logs.events())
          .allSatisfy(
              event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("advice.seeker@example.org");
                assertThat(event.getFormattedMessage()).doesNotContain(reply);
                assertThat(event.getThrowableProxy()).isNull();
              });
      // The operator still needs to tell an auth failure from a timeout.
      assertThat(logs.messages(Level.ERROR).get(0))
          .contains("smtp.example.org")
          .contains("SmtpSendException")
          .contains("MessagingException");
    }
  }

  @Test
  void causeChainIsBoundedSoAWrappedChainCannotFloodTheLog() {
    Throwable deepest = new IllegalStateException("root");
    for (int depth = 0; depth < 7; depth++) {
      deepest = new IllegalArgumentException("wrap", deepest);
    }
    assertThat(OrisoEmailDispatcher.causeChain(deepest).split(" <- ")).hasSize(5);
  }

  @Test
  void causeChainSurvivesACyclicCauseChain() {
    var first = new IllegalStateException("first");
    var second = new IllegalArgumentException("second", first);
    first.initCause(second);
    assertThat(OrisoEmailDispatcher.causeChain(first).split(" <- ")).hasSizeLessThanOrEqualTo(5);
  }
}
