package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyConsultantSessionListContractTest {

  @Test
  void consultantSessionListMustNotReadRocketChatMetadata() throws IOException {
    for (var sourcePath :
        new String[] {
          "src/main/java/de/caritas/cob/userservice/api/service/sessionlist/"
              + "ConsultantSessionListService.java",
          "src/main/java/de/caritas/cob/userservice/api/service/sessionlist/"
              + "ConsultantSessionEnricher.java",
          "src/main/java/de/caritas/cob/userservice/api/service/sessionlist/"
              + "ConsultantChatEnricher.java"
        }) {
      assertThat(Files.readString(Path.of(sourcePath)))
          .doesNotContain(
              "RocketChat",
              "rocketChat",
              "rcToken",
              "rcAuthToken",
              "RocketChatRoomInformation",
              "AvailableLastMessageUpdater",
              "SessionListAnalyser");
    }
  }
}
