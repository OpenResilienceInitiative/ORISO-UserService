package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyAnonymousConversationFinishContractTest {

  @Test
  void anonymousConversationFinishAndDeactivationMustNotScheduleLegacyRoomActions()
      throws IOException {
    for (var sourcePath :
        new String[] {
          "src/main/java/de/caritas/cob/userservice/api/conversation/facade/"
              + "FinishAnonymousConversationFacade.java",
          "src/main/java/de/caritas/cob/userservice/api/workflow/deactivate/service/"
              + "DeactivateAnonymousUserService.java"
        }) {
      assertThat(Files.readString(Path.of(sourcePath)))
          .doesNotContain("SetRocketChatRoomReadOnlyActionCommand", "RocketChat", "rocketChat");
    }
  }

  @Test
  void legacyRoomReadOnlyActionMustBeDeleted() {
    assertThat(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/actions/session/"
                    + "SetRocketChatRoomReadOnlyActionCommand.java"))
        .doesNotExist();
  }
}
