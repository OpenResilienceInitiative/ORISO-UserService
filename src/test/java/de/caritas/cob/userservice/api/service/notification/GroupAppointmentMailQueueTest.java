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

  @Test
  void aLaterSeriesDateGetsItsReminderWithoutFloodingTheInboxWithConfirmations() {
    var start = LocalDateTime.now(ZoneOffset.UTC).plusDays(5);
    var series = series(start);
    when(chats.findSeriesForAppointmentMailUpdate(42L)).thenReturn(Optional.of(series));
    when(states.findForUpdate(42L, 0)).thenReturn(Optional.empty());
    when(counselors.findBySeriesId(42L))
        .thenReturn(List.of(GroupChatParticipant.builder().consultantId("counselor").build()));

    queue.recordOccurrence(series, 0, start, start, false);

    var saved = ArgumentCaptor.forClass(GroupAppointmentMailOutbox.class);
    verify(outbox).save(saved.capture());
    assertThat(saved.getValue().getEventType()).isEqualTo(EventType.REMINDER);
    assertThat(saved.getValue().getOccurrenceRevision()).isEqualTo(1);
  }

  @Test
  void joiningARecurringSeriesConfirmsOnlyTheNextDate() {
    var start = LocalDateTime.now(ZoneOffset.UTC).plusDays(5);
    var series = series(start);
    var first =
        GroupAppointmentOccurrenceState.builder()
            .seriesId(42L)
            .occurrenceIndex(0)
            .revision(1)
            .effectiveStartUtc(start)
            .timezone("Europe/Berlin")
            .status(GroupAppointmentOccurrenceState.Status.ACTIVE)
            .build();
    var second =
        GroupAppointmentOccurrenceState.builder()
            .seriesId(42L)
            .occurrenceIndex(1)
            .revision(1)
            .effectiveStartUtc(start.plusWeeks(1))
            .timezone("Europe/Berlin")
            .status(GroupAppointmentOccurrenceState.Status.ACTIVE)
            .build();
    when(chats.findSeriesForAppointmentMailUpdate(42L)).thenReturn(Optional.of(series));
    when(states.findBySeriesId(42L)).thenReturn(List.of(second, first));

    queue.recordMemberJoined(
        series,
        new GroupAppointmentMailQueue.Member(
            GroupAppointmentMailOutbox.RecipientRole.PARTICIPANT, "participant"));

    var saved = ArgumentCaptor.forClass(GroupAppointmentMailOutbox.class);
    verify(outbox, org.mockito.Mockito.times(3)).save(saved.capture());
    assertThat(saved.getAllValues())
        .extracting(GroupAppointmentMailOutbox::getEventType)
        .containsExactlyInAnyOrder(EventType.CONFIRMED, EventType.REMINDER, EventType.REMINDER);
    assertThat(
            saved.getAllValues().stream()
                .filter(mail -> mail.getEventType() == EventType.CONFIRMED)
                .findFirst()
                .orElseThrow()
                .getOccurrenceIndex())
        .isZero();
  }

  @Test
  void aLegacyRepeatingGroupQueuesTheNewMembersNextDate() {
    var start = LocalDateTime.now(ZoneOffset.UTC).plusDays(5);
    var series = series(start);
    series.setConversationType(null);
    series.setRepeatCount(2);
    var next =
        GroupAppointmentOccurrenceState.builder()
            .seriesId(42L)
            .occurrenceIndex(0)
            .revision(1)
            .effectiveStartUtc(start)
            .timezone("Europe/Berlin")
            .status(GroupAppointmentOccurrenceState.Status.ACTIVE)
            .build();
    when(chats.findSeriesForAppointmentMailUpdate(42L)).thenReturn(Optional.of(series));
    when(states.findBySeriesId(42L)).thenReturn(List.of(next));

    queue.recordMemberJoined(
        series,
        new GroupAppointmentMailQueue.Member(
            GroupAppointmentMailOutbox.RecipientRole.COUNSELOR, "counselor"));

    var saved = ArgumentCaptor.forClass(GroupAppointmentMailOutbox.class);
    verify(outbox, org.mockito.Mockito.times(2)).save(saved.capture());
    assertThat(saved.getAllValues())
        .extracting(GroupAppointmentMailOutbox::getEventType)
        .containsExactlyInAnyOrder(EventType.CONFIRMED, EventType.REMINDER);
  }

  @Test
  void joiningConfirmsTheEarliestEffectiveDateAfterAnOverrideReordersTheSeries() {
    var start = LocalDateTime.now(ZoneOffset.UTC).plusDays(5);
    var series = series(start);
    var movedFirst =
        GroupAppointmentOccurrenceState.builder()
            .seriesId(42L)
            .occurrenceIndex(0)
            .revision(2)
            .effectiveStartUtc(start.plusWeeks(2))
            .timezone("Europe/Berlin")
            .status(GroupAppointmentOccurrenceState.Status.ACTIVE)
            .build();
    var second =
        GroupAppointmentOccurrenceState.builder()
            .seriesId(42L)
            .occurrenceIndex(1)
            .revision(1)
            .effectiveStartUtc(start.plusWeeks(1))
            .timezone("Europe/Berlin")
            .status(GroupAppointmentOccurrenceState.Status.ACTIVE)
            .build();
    when(chats.findSeriesForAppointmentMailUpdate(42L)).thenReturn(Optional.of(series));
    when(states.findBySeriesId(42L)).thenReturn(List.of(movedFirst, second));

    queue.recordMemberJoined(
        series,
        new GroupAppointmentMailQueue.Member(
            GroupAppointmentMailOutbox.RecipientRole.PARTICIPANT, "participant"));

    var saved = ArgumentCaptor.forClass(GroupAppointmentMailOutbox.class);
    verify(outbox, org.mockito.Mockito.times(3)).save(saved.capture());
    assertThat(
            saved.getAllValues().stream()
                .filter(mail -> mail.getEventType() == EventType.CONFIRMED)
                .findFirst()
                .orElseThrow()
                .getOccurrenceIndex())
        .isEqualTo(1);
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
