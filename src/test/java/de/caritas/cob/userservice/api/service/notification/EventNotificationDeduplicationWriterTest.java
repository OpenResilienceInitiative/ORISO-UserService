package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventNotificationDeduplicationWriterTest {

  @InjectMocks private EventNotificationDeduplicationWriter writer;
  @Mock private EventNotificationRepository repository;

  private EventNotification rowWith(String params) {
    var row = EventNotification.builder().params(params).build();
    when(repository.findByRecipientUserIdAndDeduplicationKey("colleague", "key"))
        .thenReturn(Optional.of(row));
    return row;
  }

  @Test
  void markMentioned_Should_KeepTheOtherParams() {
    var row = rowWith("{\"senderName\":\"Kim\"}");

    assertThat(writer.markMentionedInNewTransaction("colleague", "key")).isTrue();

    assertThat(row.getParams()).contains("\"senderName\":\"Kim\"").contains("\"mentioned\":true");
    verify(repository).saveAndFlush(row);
  }

  @Test
  void markMentioned_Should_ChangeNothing_When_TheRowIsAlreadyMarked() {
    rowWith("{\"mentioned\":true}");

    assertThat(writer.markMentionedInNewTransaction("colleague", "key")).isFalse();

    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void markMentioned_Should_ReportNothing_When_ThereIsNoRow() {
    when(repository.findByRecipientUserIdAndDeduplicationKey("colleague", "key"))
        .thenReturn(Optional.empty());

    assertThat(writer.markMentionedInNewTransaction("colleague", "key")).isFalse();
  }

  @Test
  void markMentioned_Should_Survive_UnreadableParams() {
    var row = rowWith("not json");

    assertThat(writer.markMentionedInNewTransaction("colleague", "key")).isTrue();

    assertThat(row.getParams()).isEqualTo("{\"mentioned\":true}");
  }
}
