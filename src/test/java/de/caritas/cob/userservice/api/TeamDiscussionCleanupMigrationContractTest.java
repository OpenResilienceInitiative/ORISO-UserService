package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TeamDiscussionCleanupMigrationContractTest {

  @Test
  void failedLosingRoomCleanupHasAnAppendOnlyDurableRetryTable() throws IOException {
    String master = resource("/db/changelog/userservice-master.xml");
    String changelog =
        resource("/db/changelog/changeset/0100_team_discussion_room_cleanup/0100_changeSet.xml");
    String migration =
        resource("/db/changelog/changeset/0100_team_discussion_room_cleanup/migrate.sql");

    assertThat(master).contains("0100_team_discussion_room_cleanup/0100_changeSet.xml");
    assertThat(changelog).contains("0100-team-discussion-room-cleanup").contains("<rollback>");
    assertThat(migration)
        .contains("team_discussion_room_cleanup_task")
        .contains("matrix_room_id")
        .contains("attempt_count")
        .contains("UNIQUE KEY uk_team_discussion_room_cleanup_room");
  }

  private String resource(String path) throws IOException {
    try (var stream = getClass().getResourceAsStream(path)) {
      assertThat(stream).as("resource %s", path).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
