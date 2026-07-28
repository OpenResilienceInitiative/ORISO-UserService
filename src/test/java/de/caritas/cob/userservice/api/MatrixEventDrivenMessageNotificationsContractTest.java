package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixEventDrivenMessageNotificationsContractTest {

  @Test
  void messageNotificationsMustComeFromTheMatrixEventListener() throws Exception {
    var api = Files.readString(Path.of("api/userservice.yaml"));
    var listener =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/service/matrix/"
                    + "MatrixEventListenerService.java"));

    assertThat(api)
        .doesNotContain(
            "/users/mails/messages/new",
            "operationId: sendNewMessageNotification",
            "NewMessageNotificationDTO");
    assertThat(listener).contains("createMessageNotificationFromRoom");
    assertThat(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/service/emailsupplier/"
                    + "NewMessageEmailSupplier.java"))
        .doesNotExist();
  }
}
