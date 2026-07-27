package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyTopicRoutingContractTest {

  @Test
  void topicRoutingMustNotFallBackToLegacyPresence() throws IOException {
    var source =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/service/consultingtype/"
                    + "TopicConsultantRoutingService.java"));

    assertThat(source)
        .doesNotContain(
            "Messenger",
            "messenger",
            "RocketChat",
            "rocketChat",
            "findAvailableConsultants",
            "getRocketChatId");
  }

  @Test
  void messagingPortMustNotExposeLegacyPresenceLookup() throws IOException {
    var port =
        Files.readString(
            Path.of("src/main/java/de/caritas/cob/userservice/api/port/in/Messaging.java"));
    var adapter =
        Files.readString(Path.of("src/main/java/de/caritas/cob/userservice/api/Messenger.java"));

    assertThat(port).doesNotContain("findAvailableConsultants");
    assertThat(adapter).doesNotContain("findAvailableConsultants", "findAllAvailableUserIds");
  }
}
