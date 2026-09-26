package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException.ExceptionType;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.RecipientRole;
import de.caritas.cob.userservice.api.port.out.ChatOccurrenceExceptionRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Connects the existing self-help Series to the durable appointment-mail queue. */
@Service
@RequiredArgsConstructor
public class GroupAppointmentSeriesEventProducer {
  private final GroupAppointmentMailQueue queue;
  private final ChatOccurrenceExceptionRepository exceptions;

  public void recordCreated(Chat series) {
    if (!isSelfHelp(series)) {
      return;
    }
    var byStart = exceptionsByOriginalStart(series);
    for (int index = 0; index < series.getRepeatCount(); index++) {
      var original = series.occurrenceStart(index);
      queue.recordOccurrence(
          series, index, original, effectiveStart(original, byStart.get(original)));
    }
  }

  /** Snapshot the old schedule before ChatService mutates the managed Series entity. */
  public int seedBeforeEdit(Chat series) {
    if (!isSelfHelp(series)) {
      return 0;
    }
    var byStart = exceptionsByOriginalStart(series);
    for (int index = 0; index < series.getRepeatCount(); index++) {
      var original = series.occurrenceStart(index);
      queue.seedOccurrence(
          series, index, original, effectiveStart(original, byStart.get(original)));
    }
    return series.getRepeatCount();
  }

  public void recordAfterEdit(Chat series, int oldRepeatCount) {
    if (!isSelfHelp(series)) {
      return;
    }
    var byStart = exceptionsByOriginalStart(series);
    for (int index = 0; index < series.getRepeatCount(); index++) {
      var original = series.occurrenceStart(index);
      queue.recordOccurrence(
          series, index, original, effectiveStart(original, byStart.get(original)));
    }
    for (int index = series.getRepeatCount(); index < oldRepeatCount; index++) {
      queue.recordRemovedOccurrence(series, index);
    }
  }

  /** Existing Series are baselined silently; only the newly admitted member gets confirmation. */
  public void recordMemberJoined(Chat series, RecipientRole role, String memberId) {
    if (!isSelfHelp(series)) {
      return;
    }
    var byStart = exceptionsByOriginalStart(series);
    for (int index = 0; index < series.getRepeatCount(); index++) {
      var original = series.occurrenceStart(index);
      queue.seedOccurrence(
          series, index, original, effectiveStart(original, byStart.get(original)));
    }
    queue.recordMemberJoined(series, new GroupAppointmentMailQueue.Member(role, memberId));
  }

  private Map<LocalDateTime, ChatOccurrenceException> exceptionsByOriginalStart(Chat series) {
    return exceptions.findBySeries_Id(series.getId()).stream()
        .collect(
            Collectors.toMap(
                ChatOccurrenceException::getOriginalOccurrenceStartUtc, Function.identity()));
  }

  private static LocalDateTime effectiveStart(
      LocalDateTime originalStartUtc, ChatOccurrenceException exception) {
    if (exception == null) {
      return originalStartUtc;
    }
    if (exception.getExceptionType() == ExceptionType.SKIP) {
      return null;
    }
    return exception.getOverrideStartUtc() == null
        ? originalStartUtc
        : exception.getOverrideStartUtc();
  }

  private static boolean isSelfHelp(Chat series) {
    return series != null
        && series.getId() != null
        && series.getConversationType() == ConversationType.SELF_HELP;
  }
}
