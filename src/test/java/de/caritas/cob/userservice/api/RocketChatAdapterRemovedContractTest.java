package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RocketChatAdapterRemovedContractTest {

  private static final Path MAIN_JAVA = Path.of("src/main/java");
  private static final Path USER_SERVICE_API = Path.of("api/userservice.yaml");
  private static final Path MASTER_CHANGELOG =
      Path.of("src/main/resources/db/changelog/userservice-master.xml");

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

  @Test
  void publicApiMustNotExposeRocketChatEndpointsHeadersOrDtos() throws IOException {
    assertThat(Files.readString(USER_SERVICE_API))
        .doesNotContain(
            "/users/sessions/rocketChatGroupId",
            "getRocketChatGroupId",
            "RocketChatGroupIdDTO",
            "name: RCToken",
            "name: RCUserId",
            "name: rcToken");
    assertThat(Files.readString(Path.of("api/appointmentservice.yaml")))
        .doesNotContain("name: RCToken", "name: RCUserId");

    assertThat(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/exception/httpresponses/"
                    + "RocketChatUnauthorizedException.java"))
        .doesNotExist();
    assertThat(Files.readString(Path.of("config.env.example"))).doesNotContain("ROCKET_");
  }

  @Test
  void matrixBanContractMustNotRetainRocketChatTokenOrIdentifierLength() throws IOException {
    var api = Files.readString(USER_SERVICE_API);
    var banOperation =
        api.substring(
            api.indexOf("/users/{matrixUserId}/chat/{chatId}/ban:"),
            api.indexOf("/users/chat/{chatId}/start:"));

    assertThat(banOperation)
        .doesNotContain("rcToken", "minLength: 17", "maxLength: 17")
        .contains("name: matrixUserId");
  }

  @Test
  void currentAccountModelMustNotPersistRocketChatUserIds() throws IOException {
    for (var model : new String[] {"User.java", "Consultant.java", "Admin.java"}) {
      assertThat(
              Files.readString(
                  Path.of("src/main/java/de/caritas/cob/userservice/api/model").resolve(model)))
          .as(model)
          .doesNotContain("rc_user_id", "rcUserId", "rocketChatId");
    }

    assertThat(Files.readString(MASTER_CHANGELOG))
        .contains("db/changelog/changeset/0073_remove_rocket_chat_user_ids/0073_changeSet.xml");
  }

  @Test
  void currentChatModelAndApiMustUseMatrixRoomIdentifiersOnly() throws IOException {
    for (var model : new String[] {"Session.java", "Chat.java"}) {
      assertThat(
              Files.readString(
                  Path.of("src/main/java/de/caritas/cob/userservice/api/model").resolve(model)))
          .as(model)
          .doesNotContain("rc_group_id", "groupId", "getGroupId", "setGroupId")
          .contains("matrix_room_id", "matrixRoomId");
    }

    assertThat(Files.readString(USER_SERVICE_API))
        .doesNotContain("rcGroupId", "askerRcId", "consultantRcId", "rcUserId", "initiatorRcUserId")
        .contains("matrixRoomId", "askerMatrixUserId", "consultantMatrixUserId");
    assertThat(Files.readString(Path.of("api/appointmentservice.yaml")))
        .doesNotContain("rcGroupId")
        .contains("matrixRoomId");
    assertThat(Files.readString(Path.of("api/userstatisticsservice.yaml")))
        .doesNotContain("rcGroupId")
        .contains("matrixRoomId");
    assertThat(Files.readString(Path.of("services/statisticsservice.yaml")))
        .doesNotContain("rcGroupId", "Rocket.Chat")
        .contains("matrixRoomId");
    assertThat(Files.readString(MASTER_CHANGELOG))
        .contains(
            "db/changelog/changeset/0074_remove_rocket_chat_room_ids/0074_changeSet.xml",
            "db/changelog/changeset/0075_remove_rocket_chat_feedback_room_id/0075_changeSet.xml");
    assertThat(
            Files.readString(
                Path.of(
                    "src/main/resources/db/changelog/changeset/"
                        + "0074_remove_rocket_chat_room_ids/0074_changeSet.xml")))
        .contains("rc_group_id LIKE '!%:%'")
        .contains("dropColumn tableName=\"session\" columnName=\"rc_group_id\"")
        .contains("dropColumn tableName=\"chat\" columnName=\"rc_group_id\"");
    assertThat(
            Files.readString(
                Path.of(
                    "src/main/resources/db/changelog/changeset/"
                        + "0075_remove_rocket_chat_feedback_room_id/0075_changeSet.xml")))
        .contains("dropColumn tableName=\"session\" columnName=\"rc_feedback_group_id\"");
  }

  @Test
  void legacyLiveServiceTransportMustRemainRemoved() throws IOException {
    assertThat(Path.of("services/liveservice.yaml")).doesNotExist();
    assertThat(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/adapters/live/"
                    + "LiveServiceEventGateway.java"))
        .doesNotExist();
    assertThat(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/"
                    + "LiveProxyController.java"))
        .doesNotExist();
    assertThat(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/service/liveevents/"
                    + "LiveEventNotificationService.java"))
        .doesNotExist();

    assertThat(Files.readString(Path.of("pom.xml")))
        .doesNotContain("liveservice-client-model", "services/liveservice.yaml");
    assertThat(Files.readString(USER_SERVICE_API)).doesNotContain("/liveproxy/send");
    assertThat(Files.readString(Path.of("src/main/resources/application.properties")))
        .doesNotContain("LIVE_SERVICE_API_URL", "live.service.api.url");
  }

  @Test
  void currentNotificationRequestMustNotExposeTransportModeSwitch() throws IOException {
    assertThat(
            Files.readString(
                Path.of(
                    "src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/"
                        + "EventNotificationController.java")))
        .doesNotContain("matrixRoom");
  }

  @Test
  void currentSqlFixturesMustNotUseDroppedRocketChatColumns() throws IOException {
    var userServiceDatabase =
        Files.readString(Path.of("src/test/resources/database/UserServiceDatabase.sql"));
    assertThat(userServiceDatabase).doesNotContain("rc_user_id", "rc_group_id");

    var sessionFixtures =
        userServiceDatabase.substring(
            userServiceDatabase.indexOf("INSERT INTO session"),
            userServiceDatabase.indexOf("INSERT INTO session_topic"));
    assertThat(sessionFixtures)
        .as("Matrix room fixture values must be fully-qualified room IDs")
        .doesNotMatch("(?s).*'[A-Za-z0-9]{17}'.*");

    assertThat(Files.readString(Path.of("src/test/resources/database/chatAndRelationData.sql")))
        .doesNotContain("rc_group_id");
  }
}
