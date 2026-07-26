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
            "name: RCUserId");
    assertThat(Files.readString(Path.of("api/appointmentservice.yaml")))
        .doesNotContain("name: RCToken", "name: RCUserId");

    assertThat(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/exception/httpresponses/"
                    + "RocketChatUnauthorizedException.java"))
        .doesNotExist();
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
        .doesNotContain("rcGroupId", "askerRcId", "consultantRcId")
        .contains("matrixRoomId", "askerMatrixUserId", "consultantMatrixUserId");
    assertThat(Files.readString(Path.of("api/appointmentservice.yaml")))
        .doesNotContain("rcGroupId")
        .contains("matrixRoomId");
    assertThat(Files.readString(Path.of("api/userstatisticsservice.yaml")))
        .doesNotContain("rcGroupId")
        .contains("matrixRoomId");
    assertThat(Files.readString(MASTER_CHANGELOG))
        .contains("db/changelog/changeset/0074_remove_rocket_chat_room_ids/0074_changeSet.xml");
    assertThat(
            Files.readString(
                Path.of(
                    "src/main/resources/db/changelog/changeset/"
                        + "0074_remove_rocket_chat_room_ids/0074_changeSet.xml")))
        .contains("rc_group_id LIKE '!%:%'")
        .contains("dropColumn tableName=\"session\" columnName=\"rc_group_id\"")
        .contains("dropColumn tableName=\"chat\" columnName=\"rc_group_id\"");
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
    assertThat(Files.readString(Path.of("src/test/resources/database/UserServiceDatabase.sql")))
        .doesNotContain("rc_user_id", "rc_group_id");
    assertThat(Files.readString(Path.of("src/test/resources/database/chatAndRelationData.sql")))
        .doesNotContain("rc_group_id");
  }
}
