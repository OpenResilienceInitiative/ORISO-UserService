package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixCryptoOwnershipContractTest {

  @Test
  void userServiceDoesNotExposeRocketChatE2eKeyExchange() throws IOException {
    var openApi = Files.readString(Path.of("api/userservice.yaml"));
    var messenger =
        Files.readString(Path.of("src/main/java/de/caritas/cob/userservice/api/Messenger.java"));

    assertThat(openApi)
        .doesNotContain("/users/chat/e2e")
        .doesNotContain("E2eKeyDTO")
        .doesNotContain("updateE2eInChats");
    assertThat(messenger)
        .doesNotContain("MessageClient")
        .doesNotContain("StringConverter")
        .doesNotContain("updateE2eKeys");
  }
}
