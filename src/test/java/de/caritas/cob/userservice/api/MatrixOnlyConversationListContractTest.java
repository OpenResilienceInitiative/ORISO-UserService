package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyConversationListContractTest {

  @Test
  void conversationListsMustNotAcceptOrCarryLegacyChatTokens() throws IOException {
    for (var sourcePath :
        new String[] {
          "src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/"
              + "ConversationController.java",
          "src/main/java/de/caritas/cob/userservice/api/conversation/service/"
              + "ConversationListResolver.java",
          "src/main/java/de/caritas/cob/userservice/api/conversation/model/"
              + "PageableListRequest.java"
        }) {
      assertThat(Files.readString(Path.of(sourcePath)))
          .doesNotContain("RCToken", "rcToken", "RocketChat", "rocketChat");
    }
  }

  @Test
  void conversationListApiOperationsMustNotDeclareLegacyChatTokenHeaders() throws IOException {
    var api = Files.readString(Path.of("api/conversationservice.yaml"));

    for (var operationId :
        new String[] {
          "getRegisteredEnquiries",
          "getAnonymousEnquiries",
          "getArchivedSessions",
          "getArchivedTeamSessions"
        }) {
      assertThat(operationBlock(api, operationId)).doesNotContain("RCToken", "rcToken");
    }
  }

  private String operationBlock(String api, String operationId) {
    var operationIndex = api.indexOf("operationId: " + operationId);
    assertThat(operationIndex).isGreaterThanOrEqualTo(0);
    var blockStart = api.lastIndexOf("\n  /", operationIndex);
    var blockEnd = api.indexOf("\n  /", operationIndex);
    return api.substring(blockStart, blockEnd < 0 ? api.length() : blockEnd);
  }
}
