package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Transport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class OrisoEmailDispatcherTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void adminSnapshotPreservesConfiguredTransportAndMultipartContent(boolean implicitTls)
      throws Exception {
    var snapshot =
        new PlatformSmtpSettingsProvider.Settings(
            "smtp.invalid",
            587,
            implicitTls,
            "smtp-user",
            "smtp-secret",
            "sender@example.org",
            "#123456");
    var email = new OrisoEmailRenderer.RenderedEmail("Grüße", "<p>Contact</p>", "Contact");
    var dispatcher = new OrisoEmailDispatcher();
    List<Message> messages = new ArrayList<>();

    try (MockedStatic<Transport> transport = mockStatic(Transport.class)) {
      transport
          .when(() -> Transport.send(any(Message.class)))
          .thenAnswer(
              invocation -> {
                messages.add(invocation.getArgument(0));
                return null;
              });

      assertThat(dispatcher.send(snapshot, "recipient@example.org", email)).isTrue();
      transport.verify(() -> Transport.send(any(Message.class)));
    }

    assertThat(messages)
        .hasSize(1)
        .allSatisfy(
            message -> {
              assertThat(message.getFrom()[0].toString()).isEqualTo("sender@example.org");
              assertThat(message.getAllRecipients()[0].toString())
                  .isEqualTo("recipient@example.org");
              assertThat(message.getSubject()).isEqualTo("Grüße");
              var session = message.getSession();
              assertThat(session.getProperty("mail.smtp.host")).isEqualTo("smtp.invalid");
              assertThat(session.getProperty("mail.smtp.port")).isEqualTo("587");
              assertThat(session.getProperty("mail.smtp.starttls.required"))
                  .isEqualTo(implicitTls ? null : "true");
              assertThat(session.getProperty("mail.smtp.ssl.enable"))
                  .isEqualTo(implicitTls ? "true" : null);
              assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity"))
                  .isEqualTo("true");
              var authentication =
                  session.requestPasswordAuthentication(null, 587, "smtp", null, null);
              assertThat(authentication.getUserName()).isEqualTo("smtp-user");
              assertThat(authentication.getPassword()).isEqualTo("smtp-secret");
              var content = (Multipart) message.getContent();
              assertThat(content.getCount()).isEqualTo(2);
              assertThat(content.getBodyPart(0).getContent()).isEqualTo("Contact");
              assertThat(content.getBodyPart(1).getContent()).isEqualTo("<p>Contact</p>");
            });
  }
}
