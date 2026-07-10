package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException.ExceptionType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatOccurrenceProjectorTest {

  @Test
  void projectShouldOmitAnOccurrenceWithASkipException() {
    var anchor = LocalDateTime.of(2026, 8, 1, 18, 0);
    var series =
        Chat.builder()
            .id(42L)
            .topic("Peer support")
            .initialStartDate(anchor)
            .startDate(anchor)
            .duration(60)
            .repeatCount(3)
            .repetitive(true)
            .chatInterval(ChatInterval.DAILY)
            .build();
    var skip = ChatOccurrenceException.skip(series, anchor.plusDays(1));

    var occurrences =
        new ChatOccurrenceProjector()
            .project(series, List.of(skip), anchor.minusMinutes(1), anchor.plusDays(3), 10);

    assertThat(occurrences)
        .extracting(ChatOccurrence::start)
        .containsExactly(anchor, anchor.plusDays(2));
  }

  @Test
  void projectShouldApplyASingleOccurrenceOverrideWithoutMovingLaterOccurrences() {
    var anchor = LocalDateTime.of(2026, 8, 1, 18, 0);
    var series =
        Chat.builder()
            .id(42L)
            .topic("Peer support")
            .initialStartDate(anchor)
            .startDate(anchor)
            .duration(60)
            .maxParticipants(12)
            .repeatCount(3)
            .repetitive(true)
            .chatInterval(ChatInterval.DAILY)
            .build();
    var override =
        ChatOccurrenceException.builder()
            .series(series)
            .originalOccurrenceStartUtc(anchor.plusDays(1))
            .exceptionType(ExceptionType.OVERRIDE)
            .overrideStartUtc(anchor.plusDays(1).plusHours(2))
            .overrideDuration(30)
            .overrideCapacity(5)
            .overrideModality(ChatModality.VIDEO)
            .build();

    var occurrences =
        new ChatOccurrenceProjector()
            .project(series, List.of(override), anchor, anchor.plusDays(4), 10);

    assertThat(occurrences.get(1).start()).isEqualTo(anchor.plusDays(1).plusHours(2));
    assertThat(occurrences.get(1).duration()).isEqualTo(30);
    assertThat(occurrences.get(1).capacity()).isEqualTo(5);
    assertThat(occurrences.get(1).modality()).isEqualTo(ChatModality.VIDEO);
    assertThat(occurrences.get(2).start()).isEqualTo(anchor.plusDays(2));
  }
}
