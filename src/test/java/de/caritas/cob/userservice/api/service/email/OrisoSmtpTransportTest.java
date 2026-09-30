package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OrisoSmtpTransportTest {

  @Test
  void refusesPlaintextServerBeforeAuthenticationOrMailTransfer() throws Exception {
    try (ServerSocket server = new ServerSocket(0);
        var executor = Executors.newSingleThreadExecutor()) {
      var commands = executor.submit(() -> serveWithoutStartTls(server));
      MimeMessage message =
          new MimeMessage(
              OrisoSmtpTransport.session(
                  "127.0.0.1", server.getLocalPort(), false, "smtp-user", "secret"));
      message.setFrom(new InternetAddress("from@example.org"));
      message.setRecipient(Message.RecipientType.TO, new InternetAddress("to@example.org"));
      message.setSubject("Grüße", "UTF-8");
      message.setText("Private message", "UTF-8");

      assertThrows(MessagingException.class, () -> OrisoSmtpTransport.send(message));

      List<String> received = commands.get(5, TimeUnit.SECONDS);
      assertThat(received).anyMatch(command -> command.startsWith("EHLO"));
      assertThat(received)
          .noneMatch(
              command ->
                  command.startsWith("AUTH")
                      || command.startsWith("MAIL")
                      || command.startsWith("RCPT")
                      || command.startsWith("DATA"));
    }
  }

  private static List<String> serveWithoutStartTls(ServerSocket server) throws Exception {
    try (var socket = server.accept();
        var reader =
            new BufferedReader(
                new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        var writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.US_ASCII)) {
      socket.setSoTimeout(3000);
      List<String> commands = new ArrayList<>();
      writer.println("220 test SMTP ready");
      String command;
      while ((command = reader.readLine()) != null) {
        commands.add(command);
        writer.println(command.startsWith("EHLO") ? "250 test SMTP" : "221 bye");
      }
      return commands;
    }
  }
}
