package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException.ExceptionType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ChatOccurrenceExceptionRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.notification.GroupAppointmentMailQueue;
import de.caritas.cob.userservice.api.service.notification.GroupChatLifecycleNotificationService;
import de.caritas.cob.userservice.api.service.notification.GroupChatNotificationRecipientService;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Authorized commands that alter exactly one virtual occurrence of a finite Chat Series. */
@Service
@RequiredArgsConstructor
public class ChatOccurrenceCommandService {

  private final ChatRepository chatRepository;
  private final ChatOccurrenceExceptionRepository exceptionRepository;
  private final GroupChatParticipantRepository participantRepository;
  private final GroupChatLifecycleNotificationService lifecycleNotificationService;
  private final GroupChatNotificationRecipientService notificationRecipientService;
  private final GroupAppointmentMailQueue appointmentMailQueue;

  @Transactional
  public void skip(Long seriesId, String actingConsultantId, LocalDateTime originalStartUtc) {
    assertCanModerate(seriesId, actingConsultantId);
    var series =
        chatRepository
            .findById(seriesId)
            .orElseThrow(() -> new NotFoundException("Chat Series not found"));
    int index = requireOccurrenceIndex(series, originalStartUtc);
    var existingException =
        exceptionRepository.findBySeries_IdAndOriginalOccurrenceStartUtc(
            seriesId, originalStartUtc);
    var exception =
        existingException.orElseGet(
            () -> ChatOccurrenceException.skip(series, requireStart(originalStartUtc)));
    appointmentMailQueue.seedOccurrence(
        series,
        index,
        originalStartUtc,
        existingException.isPresent()
            ? effectiveStart(originalStartUtc, existingException.get())
            : originalStartUtc);
    exception.setExceptionType(ExceptionType.SKIP);
    exception.setOverrideStartUtc(null);
    exception.setOverrideDuration(null);
    exception.setOverrideCapacity(null);
    exception.setOverrideModality(null);
    exceptionRepository.save(exception);
    appointmentMailQueue.recordOccurrence(series, index, originalStartUtc, null);
    publishCancelled(series, originalStartUtc);
  }

  @Transactional
  public void override(
      Long seriesId,
      String actingConsultantId,
      LocalDateTime originalStartUtc,
      LocalDateTime overrideStartUtc,
      Integer overrideDuration,
      Integer overrideCapacity,
      ChatModality overrideModality) {
    assertCanModerate(seriesId, actingConsultantId);
    validateOverride(overrideStartUtc, overrideDuration, overrideCapacity, overrideModality);
    var series =
        chatRepository
            .findById(seriesId)
            .orElseThrow(() -> new NotFoundException("Chat Series not found"));
    int index = requireOccurrenceIndex(series, originalStartUtc);
    var exception =
        exceptionRepository
            .findBySeries_IdAndOriginalOccurrenceStartUtc(seriesId, originalStartUtc)
            .orElseGet(
                () ->
                    ChatOccurrenceException.builder()
                        .series(series)
                        .originalOccurrenceStartUtc(requireStart(originalStartUtc))
                        .build());
    appointmentMailQueue.seedOccurrence(
        series, index, originalStartUtc, effectiveStart(originalStartUtc, exception));
    exception.setExceptionType(ExceptionType.OVERRIDE);
    exception.setOverrideStartUtc(overrideStartUtc);
    exception.setOverrideDuration(overrideDuration);
    exception.setOverrideCapacity(overrideCapacity);
    exception.setOverrideModality(overrideModality);
    exceptionRepository.save(exception);
    appointmentMailQueue.recordOccurrence(
        series,
        index,
        originalStartUtc,
        overrideStartUtc == null ? originalStartUtc : overrideStartUtc);
  }

  private void assertCanModerate(Long seriesId, String actingConsultantId) {
    var membership =
        participantRepository
            .findBySeriesIdAndConsultantId(seriesId, actingConsultantId)
            .orElseThrow(() -> new ForbiddenException("Actor is not a member of this Series"));
    if (membership.getRole() != ParticipantRole.OWNER
        && membership.getRole() != ParticipantRole.CO_MODERATOR) {
      throw new ForbiddenException("Only an Owner or Co-Moderator can edit an occurrence");
    }
  }

  private static LocalDateTime requireStart(LocalDateTime value) {
    if (value == null) {
      throw new BadRequestException("originalStartUtc is required");
    }
    return value;
  }

  private static void validateOverride(
      LocalDateTime start, Integer duration, Integer capacity, ChatModality modality) {
    if (start == null && duration == null && capacity == null && modality == null) {
      throw new BadRequestException("At least one occurrence override is required");
    }
    if (duration != null && duration <= 0) {
      throw new BadRequestException("Occurrence duration must be positive");
    }
    if (capacity != null && capacity <= 0) {
      throw new BadRequestException("Occurrence capacity must be positive");
    }
  }

  private void publishCancelled(Chat series, LocalDateTime originalStartUtc) {
    lifecycleNotificationService.createCancelledNotifications(
        series.getId(),
        occurrenceIndex(series, originalStartUtc),
        originalStartUtc,
        series.getMatrixRoomId(),
        null,
        series.getChatModality() == ChatModality.VIDEO,
        notificationRecipientService.resolveRecipientIds(series));
  }

  private Integer occurrenceIndex(Chat series, LocalDateTime originalStartUtc) {
    for (int index = 0; index < series.getRepeatCount(); index++) {
      if (series.occurrenceStart(index).equals(originalStartUtc)) {
        return index;
      }
    }
    return null;
  }

  private int requireOccurrenceIndex(Chat series, LocalDateTime originalStartUtc) {
    Integer index = occurrenceIndex(series, requireStart(originalStartUtc));
    if (index == null) {
      throw new BadRequestException("Original occurrence start is outside the Series");
    }
    return index;
  }

  private static LocalDateTime effectiveStart(
      LocalDateTime originalStartUtc, ChatOccurrenceException exception) {
    if (exception.getExceptionType() == ExceptionType.SKIP) {
      return null;
    }
    return exception.getOverrideStartUtc() == null
        ? originalStartUtc
        : exception.getOverrideStartUtc();
  }
}
