package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyGroupChatLifecycleContractTest {

  @Test
  void startingAGroupChatMustNotCallRocketChat() throws IOException {
    var source =
        Files.readString(
            Path.of("src/main/java/de/caritas/cob/userservice/api/facade/StartChatFacade.java"));

    assertThat(source)
        .doesNotContain(
            "RocketChatService",
            "RocketChatAddUserToGroupException",
            "rocketChatService",
            "Rocket.Chat");
  }

  @Test
  void joiningAndLeavingAGroupChatMustNotCallRocketChat() throws IOException {
    var source =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/facade/"
                    + "JoinAndLeaveChatFacade.java"));

    assertThat(source)
        .doesNotContain(
            "RocketChatService",
            "rocketChatService",
            "RocketChatAddUserToGroupException",
            "RocketChatRemoveUserFromGroupException",
            "retrieveRcUserId",
            "Rocket.Chat");
  }
}
