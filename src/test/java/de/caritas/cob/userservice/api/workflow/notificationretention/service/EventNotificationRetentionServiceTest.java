package de.caritas.cob.userservice.api.workflow.notificationretention.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventNotificationRetentionServiceTest {

  @InjectMocks private EventNotificationRetentionService eventNotificationRetentionService;

  @Mock private EventNotificationRepository eventNotificationRepository;

  @BeforeEach
  void setUp() {
    setField(eventNotificationRetentionService, "readRetentionDays", 90);
    setField(eventNotificationRetentionService, "absoluteRetentionDays", 365);
  }

  @Test
  void purge_appliesBothCutoffsFromTheConfiguredPeriods() {
    LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

    eventNotificationRetentionService.purgeExpiredNotifications();
    LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);

    ArgumentCaptor<LocalDateTime> readCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> absoluteCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(eventNotificationRepository).deleteReadBefore(readCutoff.capture());
    verify(eventNotificationRepository).deleteCreatedBefore(absoluteCutoff.capture());

    // Bracketed by the clock readings around the call rather than compared to a fixed duration,
    // which would round down on any elapsed time and make the test a coin flip.
    assertThat(readCutoff.getValue()).isBetween(before.minusDays(90), after.minusDays(90));
    assertThat(absoluteCutoff.getValue()).isBetween(before.minusDays(365), after.minusDays(365));
  }

  @Test
  void purge_appliesTheReadCutoffBeforeTheAbsoluteOne() {
    eventNotificationRetentionService.purgeExpiredNotifications();

    ArgumentCaptor<LocalDateTime> readCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> absoluteCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(eventNotificationRepository).deleteReadBefore(readCutoff.capture());
    verify(eventNotificationRepository).deleteCreatedBefore(absoluteCutoff.capture());

    assertThat(absoluteCutoff.getValue())
        .as("the absolute cutoff must reach further back than the read cutoff")
        .isBefore(readCutoff.getValue());
  }

  /**
   * A misconfigured or empty period must disable its cutoff, never purge everything. Read as "0
   * days of retention", a naive implementation would delete the whole table.
   */
  @Test
  void purge_treatsANonPositiveReadPeriodAsDisabled() {
    setField(eventNotificationRetentionService, "readRetentionDays", 0);

    eventNotificationRetentionService.purgeExpiredNotifications();

    verify(eventNotificationRepository, never()).deleteReadBefore(any());
    verify(eventNotificationRepository).deleteCreatedBefore(any());
  }

  @Test
  void purge_treatsANonPositiveAbsolutePeriodAsDisabled() {
    setField(eventNotificationRetentionService, "absoluteRetentionDays", -1);

    eventNotificationRetentionService.purgeExpiredNotifications();

    verify(eventNotificationRepository).deleteReadBefore(any());
    verify(eventNotificationRepository, never()).deleteCreatedBefore(any());
  }

  @Test
  void purge_doesNothingWhenBothPeriodsAreDisabled() {
    setField(eventNotificationRetentionService, "readRetentionDays", 0);
    setField(eventNotificationRetentionService, "absoluteRetentionDays", 0);

    eventNotificationRetentionService.purgeExpiredNotifications();

    verifyNoInteractions(eventNotificationRepository);
  }

  @Test
  void purge_survivesAnEmptyRun() {
    when(eventNotificationRepository.deleteReadBefore(any())).thenReturn(0);
    when(eventNotificationRepository.deleteCreatedBefore(any())).thenReturn(0);

    eventNotificationRetentionService.purgeExpiredNotifications();

    verify(eventNotificationRepository).deleteReadBefore(any());
    verify(eventNotificationRepository).deleteCreatedBefore(any());
  }
}
