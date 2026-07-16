package de.caritas.cob.userservice.api.service.notification;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GroupChatReminderServiceTest {

  @Mock private ChatRepository chatRepository;
  @Mock private GroupChatNotificationRecipientService notificationRecipientService;
  @Mock private GroupChatLifecycleNotificationService lifecycleNotificationService;
  @InjectMocks private GroupChatReminderService service;

  @Test
  void publishesOneIdempotentReminderForEachUpcomingSeriesParticipant() {
    var now = LocalDateTime.parse("2026-08-03T17:00:00");
    var start = LocalDateTime.parse("2026-08-03T18:00:00");
    var series =
        Chat.builder()
            .id(42L)
            .topic("Peer group")
            .startDate(start)
            .initialStartDate(start)
            .currentOccurrenceIndex(0)
            .repeatCount(3)
            .chatModality(Chat.ChatModality.AUDIO)
            .matrixRoomId("!room:matrix.example")
            .build();
    ReflectionTestUtils.setField(service, "leadMinutes", 60L);
    ReflectionTestUtils.setField(service, "windowMinutes", 5L);
    when(chatRepository.findAllByActiveIsFalseAndStartDateBetween(
            LocalDateTime.parse("2026-08-03T17:55:00"), start))
        .thenReturn(List.of(series));
    when(notificationRecipientService.resolveRecipientIds(series))
        .thenReturn(List.of("owner", "co-mod", "asker"));

    service.publishUpcomingReminders(now);

    verify(lifecycleNotificationService)
        .createReminderNotifications(
            42L,
            0,
            start,
            "!room:matrix.example",
            null,
            false,
            List.of("owner", "co-mod", "asker"));
    verifyNoMoreInteractions(lifecycleNotificationService);
  }
}
