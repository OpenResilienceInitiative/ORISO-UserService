package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RocketChatRoomIdMigrationContractTest {

  private static final Path CHANGESET =
      Path.of(
          "src/main/resources/db/changelog/changeset/"
              + "0074_remove_rocket_chat_room_ids/0074_changeSet.xml");

  @Test
  void migrationArchivesEveryLegacyIdentifierAndFailsClosedOnOrphans() throws Exception {
    var migration = Files.readString(CHANGESET);

    assertThat(migration)
        .contains("legacy_chat_identifier_archive")
        .contains("LEGACY_ID_REQUIRES_ACTION")
        .contains("onFail=\"HALT\"")
        .contains("provision Matrix room IDs before retrying");
  }

  @Test
  void rollbackRestoresTheOriginalIdentifiersFromTheArchive() throws Exception {
    var migration = Files.readString(CHANGESET);

    assertThat(migration)
        .contains("s.rc_group_id = a.legacy_rc_group_id")
        .contains("s.matrix_room_id = a.matrix_room_id")
        .contains("c.rc_group_id = a.legacy_rc_group_id")
        .contains("c.matrix_room_id = a.matrix_room_id")
        .doesNotContain("SET rc_group_id = matrix_room_id");
  }
}
