package de.caritas.cob.userservice.api.service.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException.ExceptionType;
import de.caritas.cob.userservice.api.port.out.ChatOccurrenceExceptionRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class GroupChatReminderServiceTest {

  private static final LocalDateTime WINDOW_END = LocalDateTime.parse("2026-08-03T18:00:00");
  private static final LocalDateTime WINDOW_START = WINDOW_END.minusMinutes(5);
  private static final List<String> RECIPIENTS = List.of("owner", "co-mod", "asker");

  @Mock private ChatRepository chatRepository;
  @Mock private ChatOccurrenceExceptionRepository exceptionRepository;
  @Mock private GroupChatNotificationRecipientService notificationRecipientService;
  @Mock private GroupChatLifecycleNotificationService lifecycleNotificationService;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private PlatformTransactionManager transactionManager;
  @InjectMocks private GroupChatReminderService service;

  @BeforeEach
  void configureWindow() {
    ReflectionTestUtils.setField(service, "leadMinutes", 60L);
    ReflectionTestUtils.setField(service, "windowMinutes", 5L);
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  }

  @Test
  void publishesReminderAtUpperWindowEdgeToTheExistingRecipientScope() {
    var series = series(WINDOW_END, 1, 0);
    givenSchedule(series);
    when(notificationRecipientService.resolveRecipientIds(series)).thenReturn(RECIPIENTS);

    service.publishUpcomingReminders(WINDOW_END.minusHours(1));

    verifyReminder(0, WINDOW_END, false);
  }

  @Test
  void skipsReminderForAnOccurrenceThatWasCancelled() {
    var series = series(WINDOW_END, 1, 0);
    givenSchedule(series, ChatOccurrenceException.skip(series, WINDOW_END));

    service.publishUpcomingReminders(WINDOW_END.minusHours(1));

    verifyNoInteractions(lifecycleNotificationService, notificationRecipientService);
  }

  @Test
  void usesTheNextNominalDateAfterAnEarlierCancellationOnALegacySeries() {
    var earlierStart = WINDOW_END.minusWeeks(1);
    var series = series(earlierStart, 3, 0);
    givenSchedule(series, ChatOccurrenceException.skip(series, earlierStart));
    when(notificationRecipientService.resolveRecipientIds(series)).thenReturn(RECIPIENTS);

    service.publishUpcomingReminders(WINDOW_END.minusHours(1));

    verifyReminder(1, WINDOW_END, false);
  }

  @Test
  void usesAnOverrideMovedIntoTheWindowAndItsEffectiveModality() {
    var original = WINDOW_END.plusDays(1);
    var series = series(original, 1, 0);
    givenSchedule(series, move(series, original, WINDOW_START));
    when(notificationRecipientService.resolveRecipientIds(series)).thenReturn(RECIPIENTS);

    service.publishUpcomingReminders(WINDOW_END.minusHours(1));

    verifyReminder(0, WINDOW_START, true);
  }

  @Test
  void skipsAnOccurrenceMovedOutOfTheWindow() {
    var series = series(WINDOW_END, 1, 0);
    givenSchedule(series, move(series, WINDOW_END, WINDOW_END.plusDays(1)));

    service.publishUpcomingReminders(WINDOW_END.minusHours(1));

    verifyNoInteractions(lifecycleNotificationService, notificationRecipientService);
  }

  @Test
  void doesNotRemindAgainForAnAlreadyConsumedOccurrenceMovedIntoTheWindow() {
    var original = WINDOW_END.minusDays(1);
    var series = series(original, 2, 1);
    givenSchedule(series, move(series, original, WINDOW_END));

    service.publishUpcomingReminders(WINDOW_END.minusHours(1));

    verifyNoInteractions(lifecycleNotificationService, notificationRecipientService);
  }

  @Test
  void doesNotUseAStaleCandidateWhenTheLockedSeriesHasStarted() {
    var series = series(WINDOW_END, 1, 0);
    series.setActive(true);
    givenCandidate(series);

    service.publishUpcomingReminders(WINDOW_END.minusHours(1));

    verifyNoInteractions(exceptionRepository, lifecycleNotificationService);
  }

  @Test
  void skipsTheLastRecurringOccurrenceIfItsRoomIsConfirmedGone() {
    var series = series(WINDOW_END.minusWeeks(1), 2, 1);
    givenSchedule(series);
    when(matrixSynapseService.isRoomConfirmedGone(series.getMatrixRoomId())).thenReturn(true);

    service.publishUpcomingReminders(WINDOW_END.minusHours(1));

    verifyNoInteractions(lifecycleNotificationService, notificationRecipientService);
  }

  @Test
  void preservesTheLastRecurringOccurrenceWhenItsRoomIsNotConfirmedGone() {
    var series = series(WINDOW_END.minusWeeks(1), 2, 1);
    givenSchedule(series);
    when(notificationRecipientService.resolveRecipientIds(series)).thenReturn(RECIPIENTS);

    service.publishUpcomingReminders(WINDOW_END.minusHours(1));

    verify(matrixSynapseService).isRoomConfirmedGone(series.getMatrixRoomId());
    verifyReminder(1, WINDOW_END, false);
  }

  private static Chat series(LocalDateTime anchor, int count, int currentIndex) {
    return Chat.builder()
        .id(42L)
        .topic("Peer group")
        .initialStartDate(anchor)
        .startDate(anchor.plusWeeks(currentIndex))
        .repeatCount(count)
        .repetitive(count > 1)
        .currentOccurrenceIndex(currentIndex)
        .chatInterval(ChatInterval.WEEKLY)
        .chatModality(ChatModality.AUDIO)
        .matrixRoomId("!room:matrix.example")
        .build();
  }

  private static ChatOccurrenceException move(
      Chat series, LocalDateTime original, LocalDateTime effective) {
    return ChatOccurrenceException.builder()
        .series(series)
        .exceptionType(ExceptionType.OVERRIDE)
        .originalOccurrenceStartUtc(original)
        .overrideStartUtc(effective)
        .overrideModality(ChatModality.VIDEO)
        .build();
  }

  private void givenSchedule(Chat series, ChatOccurrenceException... exceptions) {
    givenCandidate(series);
    when(exceptionRepository.findBySeries_Id(series.getId())).thenReturn(List.of(exceptions));
  }

  private void givenCandidate(Chat series) {
    when(chatRepository.findInactiveReminderCandidateIds(WINDOW_START, WINDOW_END))
        .thenReturn(List.of(series.getId()));
    when(chatRepository.findByIdForUpdate(series.getId())).thenReturn(Optional.of(series));
  }

  private void verifyReminder(int index, LocalDateTime effectiveStart, boolean video) {
    verify(lifecycleNotificationService)
        .createReminderNotifications(
            42L, index, effectiveStart, "!room:matrix.example", null, video, RECIPIENTS);
    verifyNoMoreInteractions(lifecycleNotificationService);
  }
}
