package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.port.out.ChatOccurrenceExceptionRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.service.chat.ChatOccurrenceProjector;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Selects upcoming inactive Series occurrences and publishes durable in-app reminders. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupChatReminderService {

  private final ChatRepository chatRepository;
  private final ChatOccurrenceExceptionRepository exceptionRepository;
  private final MatrixSynapseService matrixSynapseService;
  private final PlatformTransactionManager transactionManager;
  private final ChatOccurrenceProjector projector = new ChatOccurrenceProjector();
  private final GroupChatNotificationRecipientService notificationRecipientService;
  private final GroupChatLifecycleNotificationService lifecycleNotificationService;

  @Value("${group.chat.reminder.leadMinutes:60}")
  private long leadMinutes;

  @Value("${group.chat.reminder.windowMinutes:5}")
  private long windowMinutes;

  public void publishUpcomingReminders(LocalDateTime nowUtc) {
    var reminderAt = nowUtc.plusMinutes(leadMinutes);
    var windowStart = reminderAt.minusMinutes(windowMinutes);
    var transaction = new TransactionTemplate(transactionManager);
    for (Long seriesId : chatRepository.findInactiveReminderCandidateIds(windowStart, reminderAt)) {
      try {
        transaction.executeWithoutResult(
            status -> publishSeriesReminders(seriesId, windowStart, reminderAt));
      } catch (RuntimeException failure) {
        log.warn(
            "Could not publish reminders for group series {}: {}",
            seriesId,
            failure.getClass().getSimpleName());
      }
    }
  }

  private void publishSeriesReminders(
      Long seriesId, LocalDateTime windowStart, LocalDateTime reminderAt) {
    // Occurrence edits take the same Chat row lock before saving their exceptions.
    var series = chatRepository.findByIdForUpdate(seriesId).orElse(null);
    if (series == null
        || series.isActive()
        || series.getCurrentOccurrenceIndex() >= series.getRepeatCount()) {
      return;
    }
    var occurrences =
        projector.projectForReminder(
            series, exceptionRepository.findBySeries_Id(seriesId), windowStart, reminderAt);
    if (occurrences.isEmpty()) {
      return;
    }
    // Stopping the final recurring occurrence purges its room without advancing the cursor.
    if (series.isRepetitive()
        && series.getCurrentOccurrenceIndex() == series.getRepeatCount() - 1
        && matrixSynapseService.isRoomConfirmedGone(series.getMatrixRoomId())) {
      return;
    }

    var recipients = notificationRecipientService.resolveRecipientIds(series);
    for (var occurrence : occurrences) {
      lifecycleNotificationService.createReminderNotifications(
          seriesId,
          occurrence.occurrenceIndex(),
          occurrence.start(),
          series.getMatrixRoomId(),
          null,
          occurrence.modality() == ChatModality.VIDEO,
          recipients);
    }
  }
}
