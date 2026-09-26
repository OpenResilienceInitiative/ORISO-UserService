package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.EventType;
import de.caritas.cob.userservice.api.model.GroupAppointmentOccurrenceState;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupAppointmentMailOutboxRepository;
import de.caritas.cob.userservice.api.port.out.GroupAppointmentOccurrenceStateRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupAppointmentMailQueueTest {
  @Mock ChatRepository chats;
  @Mock GroupAppointmentOccurrenceStateRepository states;
  @Mock GroupAppointmentMailOutboxRepository outbox;
  @Mock GroupChatParticipantRepository counselors;
  @Mock UserChatRepository participants;
  @InjectMocks GroupAppointmentMailQueue queue;

  @Test
  void changingAnOccurrenceCreatesANewRevisionAndReminderForBothRoles() {
    var firstStart = LocalDateTime.now(ZoneOffset.UTC).plusDays(4);
    var movedStart = firstStart.plusHours(2);
    var series = series(firstStart);
    var state =
        GroupAppointmentOccurrenceState.builder()
            .seriesId(42L)
            .occurrenceIndex(0)
            .revision(1)
            .originalStartUtc(firstStart)
            .effectiveStartUtc(firstStart)
            .timezone("Europe/Berlin")
            .status(GroupAppointmentOccurrenceState.Status.ACTIVE)
            .build();
    when(chats.findSeriesForAppointmentMailUpdate(42L)).thenReturn(Optional.of(series));
    when(states.findForUpdate(42L, 0)).thenReturn(Optional.of(state));
    when(counselors.findBySeriesId(42L))
        .thenReturn(List.of(GroupChatParticipant.builder().consultantId("counselor").build()));
    var participant = new User();
    participant.setUserId("participant");
    when(participants.findByChat(series))
        .thenReturn(List.of(UserChat.builder().chat(series).user(participant).build()));

    queue.recordOccurrence(series, 0, firstStart, movedStart);

    assertThat(state.getRevision()).isEqualTo(2);
    assertThat(state.getEffectiveStartUtc()).isEqualTo(movedStart);
    var saved = ArgumentCaptor.forClass(GroupAppointmentMailOutbox.class);
    verify(outbox, org.mockito.Mockito.times(4)).save(saved.capture());
    assertThat(saved.getAllValues())
        .extracting(GroupAppointmentMailOutbox::getEventType)
        .containsExactlyInAnyOrder(
            EventType.RESCHEDULED, EventType.REMINDER, EventType.RESCHEDULED, EventType.REMINDER);
    assertThat(saved.getAllValues())
        .extracting(GroupAppointmentMailOutbox::getRecipientId)
        .containsExactlyInAnyOrder("counselor", "counselor", "participant", "participant");
    assertThat(saved.getAllValues())
        .allSatisfy(
            mail -> {
              assertThat(mail.getOccurrenceRevision()).isEqualTo(2);
              assertThat(mail.getScheduledStartUtc()).isEqualTo(movedStart);
            });
    assertThat(
            saved.getAllValues().stream()
                .filter(mail -> mail.getEventType() == EventType.REMINDER)
                .findFirst()
                .orElseThrow()
                .getDueAtUtc())
        .isEqualTo(movedStart.minusHours(24));
  }

  @Test
  void replayingTheSameEffectiveTimeDoesNotClaimAnotherMail() {
    var start = LocalDateTime.now(ZoneOffset.UTC).plusDays(2);
    var series = series(start);
    var state =
        GroupAppointmentOccurrenceState.builder()
            .seriesId(42L)
            .occurrenceIndex(0)
            .revision(7)
            .originalStartUtc(start)
            .effectiveStartUtc(start)
            .timezone("Europe/Berlin")
            .status(GroupAppointmentOccurrenceState.Status.ACTIVE)
            .build();
    when(chats.findSeriesForAppointmentMailUpdate(42L)).thenReturn(Optional.of(series));
    when(states.findForUpdate(42L, 0)).thenReturn(Optional.of(state));

    queue.recordOccurrence(series, 0, start, start);

    assertThat(state.getRevision()).isEqualTo(7);
    verify(states, never()).save(state);
    verify(outbox, never()).save(org.mockito.ArgumentMatchers.any());
  }

  private static Chat series(LocalDateTime start) {
    return Chat.builder()
        .id(42L)
        .topic("hidden from mail")
        .initialStartDate(start)
        .startDate(start)
        .repeatCount(1)
        .timezone("Europe/Berlin")
        .conversationType(ConversationType.SELF_HELP)
        .build();
  }
}
