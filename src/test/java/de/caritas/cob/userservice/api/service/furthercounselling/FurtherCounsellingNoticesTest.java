package de.caritas.cob.userservice.api.service.furthercounselling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FurtherCounsellingNoticesTest {

  @Test
  void recommendationBodyIsThePrefixPlusTheAgreedJson() {
    assertThat(
            FurtherCounsellingNotices.recommended(
                3L, "Bitte melden", "Frau Berg", Instant.parse("2026-10-28T09:15:00Z")))
        .isEqualTo(
            "[SYSTEM_NOTIFICATION]{\"type\":\"FURTHER_COUNSELLING_RECOMMENDED\",\"topicId\":3,"
                + "\"note\":\"Bitte melden\",\"recommendedBy\":\"Frau Berg\","
                + "\"createdAt\":\"2026-10-28T09:15:00Z\"}");
  }

  @Test
  void recommendationWithoutNoteSendsNull() {
    assertThat(
            FurtherCounsellingNotices.recommended(
                3L, null, "Frau Berg", Instant.parse("2026-10-28T09:15:00Z")))
        .contains("\"note\":null");
  }

  @Test
  void duplicateStartedNamesOnlyTheTopic() {
    // The old room must learn nothing about the new conversation, not even its id.
    assertThat(FurtherCounsellingNotices.duplicateStarted(3L))
        .isEqualTo("[SYSTEM_NOTIFICATION]{\"type\":\"TOPIC_DUPLICATE_STARTED\",\"topicId\":3}");
  }

  @Test
  void duplicateEndedNamesOnlyTheTopic() {
    assertThat(FurtherCounsellingNotices.duplicateEnded(3L))
        .isEqualTo("[SYSTEM_NOTIFICATION]{\"type\":\"TOPIC_DUPLICATE_ENDED\",\"topicId\":3}");
  }

  @Test
  void noteIsJsonEscaped() {
    assertThat(
            FurtherCounsellingNotices.recommended(
                3L, "a \"quote\"", "B", Instant.parse("2026-10-28T09:15:00Z")))
        .contains("\"note\":\"a \\\"quote\\\"\"");
  }
}
