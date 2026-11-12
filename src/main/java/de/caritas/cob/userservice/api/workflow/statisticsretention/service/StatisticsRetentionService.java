package de.caritas.cob.userservice.api.workflow.statisticsretention.service;

import de.caritas.cob.userservice.api.model.ConsultantMessageStat;
import de.caritas.cob.userservice.api.model.StatisticsSettingChange;
import de.caritas.cob.userservice.api.port.out.CommunicationStatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantMessageStatRepository;
import de.caritas.cob.userservice.api.port.out.StatisticsSettingChangeRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Retention for the statistics tables.
 *
 * <p>{@link ConsultantMessageStat} rows are deleted once they are older than the retention period.
 * They are read by the consultant's own statistics tab and, aggregated per agency or tenant for the
 * current month, by the admin dashboard. The tab's longest choice is "Last year" (1 January to 31
 * December of the previous year, up to 730 days back), so the default is 731 days and no number it
 * can ask for is cut off. Both defaults need legal confirmation.
 *
 * <p>{@link de.caritas.cob.userservice.api.model.CommunicationStat} rows (advice-seeker messages
 * and calls, no identities) follow the same period as the message counts.
 *
 * <p>{@link StatisticsSettingChange} lines are not deleted: the latest line per setting is its
 * current value, the line count is the revision the settings screen saves against, and the lines
 * show which rule applied when. Only the admin id and name are blanked after the period (366 days,
 * the longest dashboard window: the admin only records who changed a rule). A NULL admin on an old
 * line can therefore also mean "removed by retention", not only "recorded by the system".
 *
 * <p>A non-positive period switches that part off instead of deleting everything.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsRetentionService {

  private final @NonNull ConsultantMessageStatRepository consultantMessageStatRepository;
  private final @NonNull CommunicationStatRepository communicationStatRepository;
  private final @NonNull StatisticsSettingChangeRepository statisticsSettingChangeRepository;
  private final @NonNull Clock clock;

  @Value("${statistics.message-count.retention.days:731}")
  private int messageCountRetentionDays;

  @Value("${statistics.setting-change.actor-retention.days:366}")
  private int settingChangeActorRetentionDays;

  @Value("${statistics.retention.batch-size:5000}")
  private int batchSize;

  /** Runs every part; a failure in one is logged and retried on the next run, not rethrown. */
  public void purgeExpiredStatistics() {
    var now = LocalDateTime.now(clock);
    try {
      purgeMessageCounts(now);
    } catch (RuntimeException e) {
      log.error("Statistics retention could not delete expired message counts", e);
    }
    try {
      purgeCommunicationCounts(now);
    } catch (RuntimeException e) {
      log.error("Statistics retention could not delete expired communication counts", e);
    }
    try {
      anonymizeSettingChangeActors(now);
    } catch (RuntimeException e) {
      log.error("Statistics retention could not remove actors from the settings change log", e);
    }
  }

  private void purgeMessageCounts(LocalDateTime now) {
    if (messageCountRetentionDays <= 0 || batchSize <= 0) {
      return;
    }
    var cutoff = now.minusDays(messageCountRetentionDays);
    long purged = 0;
    int deleted;
    do {
      deleted = consultantMessageStatRepository.deleteBatchSentBefore(cutoff, batchSize);
      purged += deleted;
    } while (deleted >= batchSize);
    if (purged > 0) {
      log.info(
          "Statistics retention deleted {} message-count rows older than {} days",
          purged,
          messageCountRetentionDays);
    }
  }

  private void purgeCommunicationCounts(LocalDateTime now) {
    if (messageCountRetentionDays <= 0 || batchSize <= 0) {
      return;
    }
    var cutoff = now.minusDays(messageCountRetentionDays);
    long purged = 0;
    int deleted;
    do {
      deleted = communicationStatRepository.deleteBatchOccurredBefore(cutoff, batchSize);
      purged += deleted;
    } while (deleted >= batchSize);
    if (purged > 0) {
      log.info(
          "Statistics retention deleted {} communication-count rows older than {} days",
          purged,
          messageCountRetentionDays);
    }
  }

  private void anonymizeSettingChangeActors(LocalDateTime now) {
    if (settingChangeActorRetentionDays <= 0) {
      return;
    }
    int anonymized =
        statisticsSettingChangeRepository.anonymizeActorBefore(
            now.minusDays(settingChangeActorRetentionDays));
    if (anonymized > 0) {
      log.info(
          "Statistics retention removed the admin from {} settings change lines older than {} days",
          anonymized,
          settingChangeActorRetentionDays);
    }
  }
}
