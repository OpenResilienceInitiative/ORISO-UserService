package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class SelfHelpGroupContractTest {

  private static final List<String> AUTHENTICATED_OPERATIONS =
      List.of(
          "/users/chat-series/{seriesId}/occurrences:get",
          "/users/chat-series/{seriesId}/occurrences/skip:post",
          "/users/chat-series/{seriesId}/occurrences/override:put",
          "/users/chat-series/{seriesId}/participants/{consultantId}/role:put",
          "/users/chat-series/consultants:get",
          "/users/chat-series/{seriesId}/participants/{consultantId}:delete",
          "/users/chat-series/{seriesId}/transfer-ownership:post");

  @Test
  void everyChatSeriesOperationShouldDeclareBearerSecurityAndUnauthorizedResponse()
      throws IOException {
    Map<String, Object> specification =
        new Yaml().load(Files.readString(Path.of("api/userservice.yaml")));
    Map<String, Object> paths = map(specification.get("paths"));

    for (String operationReference : AUTHENTICATED_OPERATIONS) {
      String[] parts = operationReference.split(":");
      Map<String, Object> operation = map(map(paths.get(parts[0])).get(parts[1]));

      assertThat(operation.get("security")).as(operationReference + " security").isNotNull();
      assertThat(map(operation.get("responses")))
          .as(operationReference + " responses")
          .containsKey("401");
    }
  }

  @Test
  void participantSeriesMigrationShouldBackfillLegacyRowsBeforeAddingTheForeignKey()
      throws IOException {
    String changelog =
        new String(
            getClass()
                .getResourceAsStream(
                    "/db/changelog/changeset/0062_group_chat_participant_series_roles/0062_changeSet.xml")
                .readAllBytes());

    assertThat(changelog).contains("UPDATE group_chat_participant");
    assertThat(changelog.indexOf("UPDATE group_chat_participant"))
        .isLessThan(changelog.indexOf("addForeignKeyConstraint"));
    assertThat(changelog).contains("participant_role", "consultant_id_owner", "rc_group_id");
  }

  @Test
  void legacyRepeatCountRollbackShouldOnlyTouchRepeatingChats() throws IOException {
    String changelog =
        new String(
            getClass()
                .getResourceAsStream(
                    "/db/changelog/changeset/0060_self_help_group_series/0060_changeSet.xml")
                .readAllBytes());

    assertThat(changelog)
        .contains(
            "UPDATE chat SET repeat_count = 1 WHERE is_repetitive = TRUE AND repeat_count = 12");
  }

  @Test
  void legacySeriesMigrationShouldAlignCurrentOccurrenceWithTheAdvancedStartDate()
      throws IOException {
    String changelog =
        new String(
            getClass()
                .getResourceAsStream(
                    "/db/changelog/changeset/0060_self_help_group_series/0060_changeSet.xml")
                .readAllBytes());

    assertThat(changelog)
        .contains(
            "TIMESTAMPDIFF(WEEK, initial_start_date, start_date)",
            "repeat_count = current_occurrence_index + 12");
    assertThat(changelog.indexOf("TIMESTAMPDIFF(WEEK, initial_start_date, start_date)"))
        .isLessThan(changelog.indexOf("repeat_count = current_occurrence_index + 12"));

    int alignmentChangeSetStart =
        changelog.indexOf("<changeSet id=\"0060_align_legacy_repeating_chat_occurrence\"");
    String alignmentChangeSet =
        changelog.substring(
            alignmentChangeSetStart, changelog.indexOf("</changeSet>", alignmentChangeSetStart));
    assertThat(alignmentChangeSet).contains("columnName=\"is_repetitive\"");
  }

  @Test
  void ownerMutationsShouldUseAPessimisticSeriesLock() throws IOException {
    String repository =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/port/out/GroupChatParticipantRepository.java"));
    String service =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/service/chat/GroupChatRoleService.java"));

    assertThat(repository).contains("LockModeType.PESSIMISTIC_WRITE", "findBySeriesIdForUpdate");
    assertThat(service).contains("findBySeriesIdForUpdate");
  }

  @Test
  void deduplicatedNotificationWritesShouldUseANewTransactionBoundary() throws IOException {
    String service =
        Files.readString(
            Path.of(
                "src/main/java/de/caritas/cob/userservice/api/service/notification/EventNotificationService.java"));
    Path writerPath =
        Path.of(
            "src/main/java/de/caritas/cob/userservice/api/service/notification/EventNotificationDeduplicationWriter.java");

    assertThat(service).contains("deduplicationWriter.persistInNewTransaction");
    assertThat(writerPath).exists();
    assertThat(Files.readString(writerPath)).contains("Propagation.REQUIRES_NEW", "saveAndFlush");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
