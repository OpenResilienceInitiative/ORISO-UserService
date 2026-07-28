package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatrixOnlyUserSessionListContractTest {

  private static final String[] FORBIDDEN_LEGACY_TERMS = {
    "RocketChat",
    "rocketChat",
    "rcToken",
    "rcAuthToken",
    "RCToken",
    "RocketChatCredentials",
    "RocketChatRoomInformation",
    "AvailableLastMessageUpdater",
    "SessionListAnalyser"
  };

  @Test
  void userSessionListMustNotDependOnLegacyChatCredentialsOrMetadata() throws IOException {
    for (var sourcePath :
        new String[] {
          "src/main/java/de/caritas/cob/userservice/api/service/sessionlist/"
              + "UserSessionListService.java",
          "src/main/java/de/caritas/cob/userservice/api/facade/sessionlist/SessionListFacade.java",
          "src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/"
              + "UserSessionControllerDelegate.java"
        }) {
      assertThat(Files.readString(Path.of(sourcePath))).doesNotContain(FORBIDDEN_LEGACY_TERMS);
    }
  }

  @Test
  void sessionListApiOperationsMustNotDeclareLegacyChatTokenHeaders() throws IOException {
    var api = Files.readString(Path.of("api/userservice.yaml"));

    for (var operationId :
        new String[] {
          "getSessionsForAuthenticatedUser",
          "getSessionsForRoomIds",
          "getSessionForId",
          "getSessionsForAuthenticatedConsultant",
          "getTeamSessionsForAuthenticatedConsultant",
          "getChatById"
        }) {
      assertThat(operationBlock(api, operationId)).doesNotContain("RCToken", "rcToken");
    }

    assertThat(operationBlock(api, "getSessionsForRoomIds"))
        .contains("name: roomIds[]")
        .doesNotContain("rcGroupIds");
  }

  @Test
  void sessionListAuthorizationMustNotDependOnAnObsoleteQueryParameterName() throws IOException {
    var securityConfig =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/config/auth/SecurityConfig.java"));

    assertThat(securityConfig)
        .contains("HttpMethod.GET, \"/users/sessions/room\", \"/service/users/sessions/room\"")
        .doesNotContain("matrixRoomIds");
  }

  private String operationBlock(String api, String operationId) {
    var operationIndex = api.indexOf("operationId: " + operationId);
    assertThat(operationIndex).isGreaterThanOrEqualTo(0);
    var blockStart = api.lastIndexOf("\n  /", operationIndex);
    var blockEnd = api.indexOf("\n  /", operationIndex);
    return api.substring(blockStart, blockEnd < 0 ? api.length() : blockEnd);
  }
}
