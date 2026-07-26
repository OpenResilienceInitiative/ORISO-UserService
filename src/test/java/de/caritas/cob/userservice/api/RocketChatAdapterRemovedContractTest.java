package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RocketChatAdapterRemovedContractTest {

  private static final Path MAIN_JAVA = Path.of("src/main/java");

  @Test
  void productionSourceMustNotContainRocketChatAdapterOrMessageClient() throws IOException {
    assertThat(Path.of("src/main/java/de/caritas/cob/userservice/api/adapters/rocketchat"))
        .doesNotExist();
    assertThat(Path.of("src/main/java/de/caritas/cob/userservice/api/exception/rocketchat"))
        .doesNotExist();
    assertThat(Path.of("src/main/java/de/caritas/cob/userservice/api/port/out/MessageClient.java"))
        .doesNotExist();
    assertThat(Path.of("services/messageservice.yaml")).doesNotExist();

    try (var sourceFiles = Files.walk(MAIN_JAVA)) {
      for (var sourceFile :
          sourceFiles.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
        assertThat(Files.readString(sourceFile))
            .as(sourceFile.toString())
            .doesNotContain(
                "api.adapters.rocketchat",
                "api.exception.rocketchat",
                "RocketChatService",
                "MessageClient");
      }
    }

    assertThat(Files.readString(Path.of("pom.xml")))
        .doesNotContain("messageservice-client-model", "services/messageservice.yaml");
  }

  @Test
  void testsMustNotReintroduceRocketChatAdapterFixtures() throws IOException {
    try (var sourceFiles = Files.walk(Path.of("src/test/java"))) {
      for (var sourceFile :
          sourceFiles
              .filter(path -> path.getFileName().toString().endsWith(".java"))
              .filter(
                  path ->
                      !path.getFileName()
                          .toString()
                          .equals("RocketChatAdapterRemovedContractTest.java"))
              .toList()) {
        assertThat(Files.readString(sourceFile))
            .as(sourceFile.toString())
            .doesNotContain(
                "import de.caritas.cob.userservice.api.adapters.rocketchat",
                "import de.caritas.cob.userservice.api.exception.rocketchat");
      }
    }
  }
}
