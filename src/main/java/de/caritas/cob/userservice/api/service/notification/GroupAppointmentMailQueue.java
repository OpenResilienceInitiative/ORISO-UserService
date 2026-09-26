package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.EventType;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.RecipientRole;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.Status;
import de.caritas.cob.userservice.api.model.GroupAppointmentOccurrenceState;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupAppointmentMailOutboxRepository;
import de.caritas.cob.userservice.api.port.out.GroupAppointmentOccurrenceStateRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records appointment events and reminders before any transport is attempted. */
@Service
@RequiredArgsConstructor
public class GroupAppointmentMailQueue {
  public record Member(RecipientRole role, String id) {}

  private final ChatRepository chats;
  private final GroupAppointmentOccurrenceStateRepository states;
  private final GroupAppointmentMailOutboxRepository outbox;
  private final GroupChatParticipantRepository counselors;
  private final UserChatRepository participants;

  /** Establishes a baseline for a group that existed before appointment mail was enabled. */
  @Transactional
  public void seedOccurrence(
      Chat series, int index, LocalDateTime originalStartUtc, LocalDateTime effectiveStartUtc) {
    if (!isSelfHelp(series)) {
      return;
    }
    requireIndex(series, index);
    lockSeries(series.getId());
    if (states.findForUpdate(series.getId(), index).isPresent()) {
      return;
    }
    states.save(
        GroupAppointmentOccurrenceState.builder()
            .seriesId(series.getId())
            .occurrenceIndex(index)
            .revision(1)
            .originalStartUtc(Objects.requireNonNull(originalStartUtc))
            .effectiveStartUtc(effectiveStartUtc)
            .timezone(Objects.requireNonNull(series.getTimezone()))
            .status(
                effectiveStartUtc == null
                    ? GroupAppointmentOccurrenceState.Status.CANCELLED
                    : GroupAppointmentOccurrenceState.Status.ACTIVE)
            .build());
  }

  /**
   * Records the current effective time for one Series occurrence. Replaying an unchanged schedule
   * leaves its revision and delivery claims untouched. A moved or cancelled occurrence gets a new
   * revision, so the worker can suppress a reminder from the old revision.
   */
  @Transactional
  public void recordOccurrence(
      Chat series, int index, LocalDateTime originalStartUtc, LocalDateTime effectiveStartUtc) {
    recordOccurrence(series, index, originalStartUtc, effectiveStartUtc, true);
  }

  @Transactional
  public void recordOccurrence(
      Chat series,
      int index,
      LocalDateTime originalStartUtc,
      LocalDateTime effectiveStartUtc,
      boolean notifyInitialDate) {
    if (!isSelfHelp(series)) {
      return;
    }
    requireIndex(series, index);
    lockSeries(series.getId());
    var current = states.findForUpdate(series.getId(), index);
    if (current.isPresent()
        && Objects.equals(current.get().getOriginalStartUtc(), originalStartUtc)
        && Objects.equals(current.get().getEffectiveStartUtc(), effectiveStartUtc)
        && Objects.equals(current.get().getTimezone(), series.getTimezone())) {
      return;
    }

    var previous = current.orElse(null);
    var state =
        previous == null
            ? GroupAppointmentOccurrenceState.builder()
                .seriesId(series.getId())
                .occurrenceIndex(index)
                .revision(1)
                .build()
            : previous;
    var oldEffectiveStart = state.getEffectiveStartUtc();
    state.setRevision(previous == null ? 1 : state.getRevision() + 1);
    state.setOriginalStartUtc(Objects.requireNonNull(originalStartUtc));
    state.setEffectiveStartUtc(effectiveStartUtc);
    state.setTimezone(Objects.requireNonNull(series.getTimezone()));
    state.setStatus(
        effectiveStartUtc == null
            ? GroupAppointmentOccurrenceState.Status.CANCELLED
            : GroupAppointmentOccurrenceState.Status.ACTIVE);
    states.save(state);

    var event =
        effectiveStartUtc == null
            ? EventType.CANCELLED
            : previous == null ? EventType.CONFIRMED : EventType.RESCHEDULED;
    var displayedStart = effectiveStartUtc == null ? oldEffectiveStart : effectiveStartUtc;
    if (displayedStart == null
        || !displayedStart.isAfter(nowUtc())
        || (previous == null && effectiveStartUtc == null)) {
      return;
    }
    for (var member : members(series)) {
      if (previous != null || notifyInitialDate) {
        enqueue(series, state, event, member, displayedStart);
      }
      if (effectiveStartUtc != null) {
        enqueueReminder(series, state, member, effectiveStartUtc);
      }
    }
  }

  /** A new member receives the current dates without resending to existing members. */
  @Transactional
  public void recordMemberJoined(Chat series, Member member) {
    if (!isSelfHelp(series) || member == null || member.id() == null || member.id().isBlank()) {
      return;
    }
    lockSeries(series.getId());
    boolean nextDateConfirmed = false;
    for (var state :
        states.findBySeriesId(series.getId()).stream()
            .sorted(Comparator.comparingInt(GroupAppointmentOccurrenceState::getOccurrenceIndex))
            .toList()) {
      if (state.getStatus() != GroupAppointmentOccurrenceState.Status.ACTIVE
          || state.getEffectiveStartUtc() == null
          || !state.getEffectiveStartUtc().isAfter(nowUtc())) {
        continue;
      }
      if (!nextDateConfirmed
          && !outbox
              .existsBySeriesIdAndOccurrenceIndexAndOccurrenceRevisionAndRecipientRoleAndRecipientIdAndEventTypeIn(
                  series.getId(),
                  state.getOccurrenceIndex(),
                  state.getRevision(),
                  member.role(),
                  member.id(),
                  List.of(EventType.CONFIRMED, EventType.RESCHEDULED))) {
        enqueue(series, state, EventType.CONFIRMED, member, state.getEffectiveStartUtc());
      }
      nextDateConfirmed = true;
      enqueueReminder(series, state, member, state.getEffectiveStartUtc());
    }
  }

  /** A shorter Series cancels dates that no longer have an occurrence index in the new Series. */
  @Transactional
  public void recordRemovedOccurrence(Chat series, int index) {
    if (!isSelfHelp(series)) {
      return;
    }
    lockSeries(series.getId());
    var current = states.findForUpdate(series.getId(), index);
    if (current.isEmpty()
        || current.get().getStatus() == GroupAppointmentOccurrenceState.Status.CANCELLED) {
      return;
    }
    var state = current.get();
    var oldStart = state.getEffectiveStartUtc();
    state.setRevision(state.getRevision() + 1);
    state.setStatus(GroupAppointmentOccurrenceState.Status.CANCELLED);
    state.setEffectiveStartUtc(null);
    states.save(state);
    if (oldStart != null && oldStart.isAfter(nowUtc())) {
      for (var member : members(series)) {
        enqueue(series, state, EventType.CANCELLED, member, oldStart);
      }
    }
  }

  private Set<Member> members(Chat series) {
    var members = new LinkedHashSet<Member>();
    counselors.findBySeriesId(series.getId()).stream()
        .map(member -> new Member(RecipientRole.COUNSELOR, member.getConsultantId()))
        .filter(member -> member.id() != null && !member.id().isBlank())
        .forEach(members::add);
    participants.findByChat(series).stream()
        .filter(relation -> relation.getUser() != null)
        .map(relation -> new Member(RecipientRole.PARTICIPANT, relation.getUser().getUserId()))
        .filter(member -> member.id() != null && !member.id().isBlank())
        .forEach(members::add);
    return members;
  }

  private void enqueueReminder(
      Chat series,
      GroupAppointmentOccurrenceState state,
      Member member,
      LocalDateTime effectiveStartUtc) {
    var dueAtUtc = effectiveStartUtc.minusHours(24);
    if (dueAtUtc.isAfter(nowUtc())) {
      enqueue(series, state, EventType.REMINDER, member, effectiveStartUtc, dueAtUtc);
    }
  }

  private void enqueue(
      Chat series,
      GroupAppointmentOccurrenceState state,
      EventType event,
      Member member,
      LocalDateTime displayedStart) {
    enqueue(series, state, event, member, displayedStart, nowUtc());
  }

  private void enqueue(
      Chat series,
      GroupAppointmentOccurrenceState state,
      EventType event,
      Member member,
      LocalDateTime displayedStart,
      LocalDateTime dueAtUtc) {
    if (outbox
        .existsBySeriesIdAndOccurrenceIndexAndOccurrenceRevisionAndEventTypeAndRecipientRoleAndRecipientId(
            series.getId(),
            state.getOccurrenceIndex(),
            state.getRevision(),
            event,
            member.role(),
            member.id())) {
      return;
    }
    outbox.save(
        GroupAppointmentMailOutbox.builder()
            .seriesId(series.getId())
            .occurrenceIndex(state.getOccurrenceIndex())
            .occurrenceRevision(state.getRevision())
            .eventType(event)
            .recipientRole(member.role())
            .recipientId(member.id())
            .correlationId(UUID.randomUUID().toString())
            .scheduledStartUtc(displayedStart)
            .timezone(state.getTimezone())
            .dueAtUtc(dueAtUtc)
            .status(Status.PENDING)
            .createdAt(nowUtc())
            .build());
  }

  private void lockSeries(Long seriesId) {
    chats
        .findSeriesForAppointmentMailUpdate(seriesId)
        .orElseThrow(() -> new IllegalStateException("Appointment Series no longer exists"));
  }

  private static boolean isSelfHelp(Chat series) {
    return series != null
        && series.getId() != null
        && series.getConversationType() == ConversationType.SELF_HELP;
  }

  private static void requireIndex(Chat series, int index) {
    if (index < 0 || index >= series.getRepeatCount()) {
      throw new IllegalArgumentException("Occurrence index is outside the Series");
    }
  }

  private static LocalDateTime nowUtc() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }
}
