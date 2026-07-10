package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Selects upcoming inactive Series occurrences and publishes durable in-app reminders. */
@Service
@RequiredArgsConstructor
public class GroupChatReminderService {

  private final ChatRepository chatRepository;
  private final GroupChatNotificationRecipientService notificationRecipientService;
  private final GroupChatLifecycleNotificationService lifecycleNotificationService;

  @Value("${group.chat.reminder.leadMinutes:60}")
  private long leadMinutes;

  @Value("${group.chat.reminder.windowMinutes:5}")
  private long windowMinutes;

  public void publishUpcomingReminders(LocalDateTime nowUtc) {
    var reminderAt = nowUtc.plusMinutes(leadMinutes);
    var windowStart = reminderAt.minusMinutes(windowMinutes);
    chatRepository.findAllByActiveIsFalseAndStartDateBetween(windowStart, reminderAt).stream()
        .filter(series -> series.getId() != null)
        .filter(series -> series.getCurrentOccurrenceIndex() < series.getRepeatCount())
        .forEach(
            series -> {
              lifecycleNotificationService.createReminderNotifications(
                  series.getId(),
                  series.getCurrentOccurrenceIndex(),
                  series.getStartDate(),
                  series.getMatrixRoomId() != null ? series.getMatrixRoomId() : series.getGroupId(),
                  null,
                  series.getChatModality() == ChatModality.VIDEO,
                  notificationRecipientService.resolveRecipientIds(series));
            });
  }
}
