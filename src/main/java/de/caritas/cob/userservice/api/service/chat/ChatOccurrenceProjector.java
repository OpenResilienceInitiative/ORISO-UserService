package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException.ExceptionType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ChatOccurrenceProjector {

  public List<ChatOccurrence> project(
      Chat series,
      List<ChatOccurrenceException> exceptions,
      LocalDateTime windowStart,
      LocalDateTime windowEnd,
      int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Occurrence projection limit must be between 1 and 100");
    }
    if (!windowStart.isBefore(windowEnd)) {
      throw new IllegalArgumentException("Occurrence projection window must be positive");
    }

    Map<LocalDateTime, ChatOccurrenceException> exceptionsByStart =
        exceptions.stream()
            .collect(
                Collectors.toMap(
                    ChatOccurrenceException::getOriginalOccurrenceStartUtc, Function.identity()));

    var result = new java.util.ArrayList<ChatOccurrence>();
    for (int index = 0; index < series.getRepeatCount() && result.size() < limit; index++) {
      var originalStart = series.occurrenceStart(index);
      var exception = exceptionsByStart.get(originalStart);
      if (exception != null && exception.getExceptionType() == ExceptionType.SKIP) {
        continue;
      }
      var effectiveStart =
          exception != null && exception.getOverrideStartUtc() != null
              ? exception.getOverrideStartUtc()
              : originalStart;
      var duration =
          exception != null && exception.getOverrideDuration() != null
              ? exception.getOverrideDuration()
              : series.getDuration();
      var capacity =
          exception != null && exception.getOverrideCapacity() != null
              ? exception.getOverrideCapacity()
              : series.getMaxParticipants();
      var modality =
          exception != null && exception.getOverrideModality() != null
              ? exception.getOverrideModality()
              : series.getChatModality();
      if (!effectiveStart.isBefore(windowStart) && effectiveStart.isBefore(windowEnd)) {
        result.add(
            new ChatOccurrence(
                series.getId(),
                index,
                originalStart,
                effectiveStart,
                duration,
                capacity,
                modality));
      }
    }
    return List.copyOf(result);
  }
}
