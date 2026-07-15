package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ChatOccurrenceExceptionRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.notification.GroupChatLifecycleNotificationService;
import de.caritas.cob.userservice.api.service.notification.GroupChatNotificationRecipientService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatOccurrenceCommandServiceTest {

  @Mock private ChatRepository chatRepository;
  @Mock private ChatOccurrenceExceptionRepository exceptionRepository;
  @Mock private GroupChatParticipantRepository participantRepository;
  @Mock private GroupChatLifecycleNotificationService lifecycleNotificationService;
  @Mock private GroupChatNotificationRecipientService notificationRecipientService;
  @InjectMocks private ChatOccurrenceCommandService service;

  @Test
  void ownerCanSkipExactlyOneOccurrence() {
    var originalStart = LocalDateTime.parse("2026-08-03T18:00:00");
    var series =
        Chat.builder()
            .id(42L)
            .topic("Peer group")
            .initialStartDate(originalStart)
            .startDate(originalStart)
            .repeatCount(2)
            .currentOccurrenceIndex(0)
            .chatModality(Chat.ChatModality.TEXT)
            .matrixRoomId("!room:matrix.example")
            .build();
    when(chatRepository.findById(42L)).thenReturn(Optional.of(series));
    when(participantRepository.findBySeriesIdAndConsultantId(42L, "owner"))
        .thenReturn(Optional.of(participant(ParticipantRole.OWNER)));
    when(notificationRecipientService.resolveRecipientIds(series))
        .thenReturn(java.util.List.of("owner", "co-mod", "asker"));

    service.skip(42L, "owner", originalStart);

    var saved = ArgumentCaptor.forClass(ChatOccurrenceException.class);
    verify(exceptionRepository).save(saved.capture());
    org.assertj.core.api.Assertions.assertThat(saved.getValue().getSeries()).isSameAs(series);
    org.assertj.core.api.Assertions.assertThat(saved.getValue().getOriginalOccurrenceStartUtc())
        .isEqualTo(originalStart);
    org.assertj.core.api.Assertions.assertThat(saved.getValue().getExceptionType())
        .isEqualTo(ChatOccurrenceException.ExceptionType.SKIP);
    verify(lifecycleNotificationService)
        .createCancelledNotifications(
            42L,
            0,
            originalStart,
            "!room:matrix.example",
            null,
            false,
            java.util.List.of("owner", "co-mod", "asker"));
  }

  @Test
  void participantCannotChangeAnOccurrence() {
    when(participantRepository.findBySeriesIdAndConsultantId(42L, "participant"))
        .thenReturn(Optional.of(participant(ParticipantRole.PARTICIPANT)));

    assertThatThrownBy(
            () ->
                service.override(
                    42L,
                    "participant",
                    LocalDateTime.parse("2026-08-03T18:00:00"),
                    LocalDateTime.parse("2026-08-03T19:00:00"),
                    90,
                    12,
                    Chat.ChatModality.TEXT))
        .isInstanceOf(ForbiddenException.class);
    verify(exceptionRepository, org.mockito.Mockito.never()).save(any());
  }

  private static GroupChatParticipant participant(ParticipantRole role) {
    return GroupChatParticipant.builder().role(role).build();
  }
}
