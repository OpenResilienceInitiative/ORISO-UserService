package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox;
import de.caritas.cob.userservice.api.model.GroupAppointmentOccurrenceState;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupAppointmentOccurrenceStateRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rechecks a queued mail against the current occurrence and recipient before delivery. */
@Service
@RequiredArgsConstructor
public class GroupAppointmentMailEligibilityService {
  public record Eligible(
      Chat series,
      GroupAppointmentOccurrenceState occurrence,
      GroupAppointmentEmailRecipientService.Recipient recipient) {}

  private final ChatRepository chats;
  private final GroupAppointmentOccurrenceStateRepository occurrences;
  private final GroupAppointmentEmailRecipientService recipients;

  @Transactional(readOnly = true)
  public Optional<Eligible> resolve(GroupAppointmentMailOutbox mail) {
    if (mail == null || mail.getScheduledStartUtc() == null) {
      return Optional.empty();
    }
    var series = chats.findById(mail.getSeriesId());
    if (series.isEmpty() || series.get().getConversationType() != ConversationType.SELF_HELP) {
      return Optional.empty();
    }
    var occurrence =
        occurrences.findBySeriesIdAndOccurrenceIndex(mail.getSeriesId(), mail.getOccurrenceIndex());
    if (occurrence.isEmpty() || occurrence.get().getRevision() != mail.getOccurrenceRevision()) {
      return Optional.empty();
    }
    boolean cancelled = mail.getEventType() == GroupAppointmentMailOutbox.EventType.CANCELLED;
    if (cancelled
            != (occurrence.get().getStatus() == GroupAppointmentOccurrenceState.Status.CANCELLED)
        || (!cancelled
            && !Objects.equals(
                occurrence.get().getEffectiveStartUtc(), mail.getScheduledStartUtc()))
        || !Objects.equals(occurrence.get().getTimezone(), mail.getTimezone())
        || !mail.getScheduledStartUtc().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
      return Optional.empty();
    }
    var role =
        switch (mail.getRecipientRole()) {
          case PARTICIPANT -> GroupAppointmentEmailRecipientService.Role.PARTICIPANT;
          case COUNSELOR -> GroupAppointmentEmailRecipientService.Role.COUNSELOR;
        };
    return recipients
        .resolve(series.get(), role, mail.getRecipientId())
        .map(recipient -> new Eligible(series.get(), occurrence.get(), recipient));
  }
}
