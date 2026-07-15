package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConversationTypeLiquibaseContractTest {

  @Test
  void masterRegistersConversationTypeMigrationWithBothColumnsAndBackfill() throws IOException {
    String master = resource("/db/changelog/userservice-master.xml");
    String changelog =
        resource("/db/changelog/changeset/0065_conversation_type/0065_changeSet.xml");

    assertThat(master).contains("0065_conversation_type/0065_changeSet.xml");
    assertThat(changelog)
        .contains("tableName=\"session\" columnName=\"conversation_type\"")
        .contains("tableName=\"chat\" columnName=\"conversation_type\"")
        .contains("AGENCY_COUNSELLING", "LIVE_CHAT", "INTERNAL_GROUP", "SELF_HELP")
        .contains(
            "is_team_session",
            "registration_type",
            "is_repetitive",
            "repeat_count",
            "source_language")
        .contains("onFail=\"MARK_RAN\"");
  }

  private String resource(String path) throws IOException {
    try (var stream = getClass().getResourceAsStream(path)) {
      assertThat(stream).as("resource %s", path).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
