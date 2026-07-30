package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyAccountManagerContractTest {

  @Test
  void accountLookupMustNotDependOnRocketChatState() throws IOException {
    var source =
        Files.readString(
            Path.of("src/main/java/de/caritas/cob/userservice/api/AccountManager.java"));

    assertThat(source)
        .doesNotContain(
            "rocket-chat.enabled",
            "MessageClient",
            "getRocketChatId",
            "Rocket.Chat",
            "rocketChatEnabled");
  }
}
