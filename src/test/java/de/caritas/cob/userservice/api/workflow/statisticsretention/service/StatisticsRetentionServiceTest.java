package de.caritas.cob.userservice.api.workflow.statisticsretention.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.port.out.CommunicationStatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantMessageStatRepository;
import de.caritas.cob.userservice.api.port.out.StatisticsSettingChangeRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

@ExtendWith(MockitoExtension.class)
class StatisticsRetentionServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-03T03:30:00Z");

  @Mock private ConsultantMessageStatRepository messageStatRepository;
  @Mock private StatisticsSettingChangeRepository settingChangeRepository;
  @Mock private CommunicationStatRepository communicationStatRepository;

  private StatisticsRetentionService service;

  @BeforeEach
  void setUp() {
    service =
        new StatisticsRetentionService(
            messageStatRepository,
            communicationStatRepository,
            settingChangeRepository,
            Clock.fixed(NOW, ZoneOffset.UTC));
    setField(service, "messageCountRetentionDays", 731);
    setField(service, "settingChangeActorRetentionDays", 366);
    setField(service, "batchSize", 1000);
  }

  @Test
  void purge_deletesMessageRowsOlderThanTheRetentionPeriod_inBatchesUntilNoneAreLeft() {
    var cutoff = LocalDateTime.of(2024, 11, 2, 3, 30);
    when(messageStatRepository.deleteBatchSentBefore(cutoff, 1000)).thenReturn(1000, 1000, 17);

    service.purgeExpiredStatistics();

    verify(messageStatRepository, times(3)).deleteBatchSentBefore(cutoff, 1000);
  }

  @Test
  void purge_deletesCommunicationRowsOlderThanTheRetentionPeriod_inBatches() {
    var cutoff = LocalDateTime.of(2024, 11, 2, 3, 30);
    when(communicationStatRepository.deleteBatchOccurredBefore(cutoff, 1000)).thenReturn(1000, 3);

    service.purgeExpiredStatistics();

    verify(communicationStatRepository, times(2)).deleteBatchOccurredBefore(cutoff, 1000);
  }

  @Test
  void purge_stillPurgesCommunicationRows_whenMessageCountPurgeFails() {
    var cutoff = LocalDateTime.of(2024, 11, 2, 3, 30);
    when(messageStatRepository.deleteBatchSentBefore(cutoff, 1000))
        .thenThrow(new IllegalStateException("db down"));

    service.purgeExpiredStatistics();

    verify(communicationStatRepository).deleteBatchOccurredBefore(cutoff, 1000);
  }

  @Test
  void purge_stopsAfterOneCall_when_fewerRowsThanTheBatchAreLeft() {
    var cutoff = LocalDateTime.of(2024, 11, 2, 3, 30);
    when(messageStatRepository.deleteBatchSentBefore(cutoff, 1000)).thenReturn(0);

    service.purgeExpiredStatistics();

    verify(messageStatRepository).deleteBatchSentBefore(cutoff, 1000);
  }

  @Test
  void purge_removesTheAdminFromOldChangeLogLines_butDoesNotDeleteThem() {
    service.purgeExpiredStatistics();

    verify(settingChangeRepository).anonymizeActorBefore(LocalDateTime.of(2025, 11, 2, 3, 30));
    verify(settingChangeRepository, never()).deleteAll();
    verify(settingChangeRepository, never()).delete(any());
  }

  /** A misconfigured or empty value must never wipe the table. */
  @Test
  void purge_leavesMessageRowsAlone_when_theRetentionIsNotPositive() {
    setField(service, "messageCountRetentionDays", 0);

    service.purgeExpiredStatistics();

    verifyNoInteractions(messageStatRepository);
    verify(settingChangeRepository).anonymizeActorBefore(any());
  }

  @Test
  void purge_leavesTheChangeLogAlone_when_theRetentionIsNotPositive() {
    setField(service, "settingChangeActorRetentionDays", -1);

    service.purgeExpiredStatistics();

    verifyNoInteractions(settingChangeRepository);
  }

  /** The two parts are independent: a failing delete is logged and retried on the next run. */
  @Test
  void purge_stillAnonymizesTheChangeLog_when_deletingMessageRowsFails() {
    when(messageStatRepository.deleteBatchSentBefore(any(), anyInt()))
        .thenThrow(new RuntimeException("db down"));

    assertThatCode(() -> service.purgeExpiredStatistics()).doesNotThrowAnyException();

    verify(settingChangeRepository).anonymizeActorBefore(any());
  }

  @Test
  void purge_stillDeletesMessageRows_when_anonymizingTheChangeLogFails() {
    when(settingChangeRepository.anonymizeActorBefore(any()))
        .thenThrow(new RuntimeException("db down"));

    assertThatCode(() -> service.purgeExpiredStatistics()).doesNotThrowAnyException();

    verify(messageStatRepository).deleteBatchSentBefore(any(), anyInt());
  }

  /**
   * The only reader of the message counts is the consultant's own statistics tab, whose longest
   * choice is "Last year": 1 January to 31 December of the previous year. Asked on 31 December,
   * that reaches back at most 731 days, so the configured default must not be shorter.
   */
  @Test
  void defaultMessageCountRetention_coversTheLongestWindowTheStatisticsTabCanAsk()
      throws Exception {
    long longestLookBack = 0;
    for (int year = 2020; year <= 2040; year++) {
      longestLookBack =
          Math.max(
              longestLookBack,
              ChronoUnit.DAYS.between(LocalDate.of(year - 1, 1, 1), LocalDate.of(year, 12, 31)));
    }
    assertThat(longestLookBack).isEqualTo(730);

    var properties = new Properties();
    try (var input = new ClassPathResource("application.properties").getInputStream()) {
      properties.load(input);
    }
    var raw = properties.getProperty("statistics.message-count.retention.days");
    // The default is the text after the last ':' and before the closing '}'.
    // It must keep the longest look-back plus the day on which the oldest row is still inside.
    int configuredDefault =
        Integer.parseInt(raw.substring(raw.lastIndexOf(':') + 1, raw.length() - 1));
    assertThat(configuredDefault).isGreaterThan((int) longestLookBack);
  }
}
