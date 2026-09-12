package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TeamAgencySessionModalityChangelogContractTest {

  @Test
  void masterRegistersTheCollisionFreeAndGuarded0093Migration() throws IOException {
    String master = resource("/db/changelog/userservice-master.xml");
    String changelog =
        resource("/db/changelog/changeset/0093_team_agency_session_modality/0093_changeSet.xml");
    String migration =
        resource("/db/changelog/changeset/0093_team_agency_session_modality/migrate.sql");

    assertThat(master)
        .contains("0091_account_invite_active_recipient/0091_changeSet.xml")
        .contains("0093_team_agency_session_modality/0093_changeSet.xml")
        .doesNotContain("0091_team_agency_session_modality");
    assertThat(changelog)
        .contains("id=\"0093-team-agency-session-modality\"")
        .contains("tableName=\"group_chat_participant\" columnName=\"chat_id\"")
        .doesNotContain("runOnChange");
    assertThat(migration)
        .contains("conversation_type = 'INTERNAL_GROUP' OR conversation_type IS NULL")
        .contains("NOT EXISTS (SELECT 1 FROM group_chat_participant")
        .contains("NOT EXISTS (SELECT 1 FROM chat")
        .contains("AND session.user_id NOT LIKE 'group-chat-system%'");
  }

  private String resource(String path) throws IOException {
    try (var stream = getClass().getResourceAsStream(path)) {
      assertThat(stream).as("resource %s", path).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
