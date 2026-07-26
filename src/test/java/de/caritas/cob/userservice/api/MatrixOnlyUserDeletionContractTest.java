package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyUserDeletionContractTest {

  @Test
  void accountDeletionMustNotScheduleRocketChatUserActions() throws IOException {
    var source =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/workflow/delete/service/"
                    + "DeleteUserAccountService.java"));

    assertThat(source)
        .doesNotContain(
            "DeleteRocketChatAskerAction", "DeleteRocketChatConsultantAction", "RocketChat");
  }

  @Test
  void RocketChatUserDeletionActionsMustBeDeleted() {
    for (var sourcePath :
        new String[] {
          "src/main/java/de/caritas/cob/userservice/api/workflow/delete/action/"
              + "DeleteRocketChatUserAction.java",
          "src/main/java/de/caritas/cob/userservice/api/workflow/delete/action/asker/"
              + "DeleteRocketChatAskerAction.java",
          "src/main/java/de/caritas/cob/userservice/api/workflow/delete/action/consultant/"
              + "DeleteRocketChatConsultantAction.java"
        }) {
      assertThat(Path.of(sourcePath)).doesNotExist();
    }
  }

  @Test
  void sessionDeletionMustNotCallRocketChat() throws IOException {
    for (var sourcePath :
        new String[] {
          "src/main/java/de/caritas/cob/userservice/api/workflow/delete/action/asker/"
              + "DeleteRoomsAndSessionAction.java",
          "src/main/java/de/caritas/cob/userservice/api/workflow/delete/action/asker/"
              + "DeleteAskerRoomsAndSessionsAction.java",
          "src/main/java/de/caritas/cob/userservice/api/workflow/delete/action/asker/"
              + "DeleteSingleRoomAndSessionAction.java"
        }) {
      assertThat(Files.readString(Path.of(sourcePath)))
          .doesNotContain("RocketChat", "rocketChat", "ROCKET_CHAT");
    }
  }
}
