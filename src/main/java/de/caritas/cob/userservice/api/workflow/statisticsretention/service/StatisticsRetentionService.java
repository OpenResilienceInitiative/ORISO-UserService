package de.caritas.cob.userservice.api.workflow.statisticsretention.service;

import de.caritas.cob.userservice.api.model.ConsultantMessageStat;
import de.caritas.cob.userservice.api.model.StatisticsSettingChange;
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
 * Retention for the two statistics tables that had none.
 *
 * <p>{@link ConsultantMessageStat} rows are deleted once they are older than the retention period.
 * The only place that reads them is the consultant's own statistics tab. The default of 366 days is
 * the longest window the admin dashboard can show (12 months of "most frequent topics", the current
 * month included), so no view loses a number it could have shown. Both defaults need legal
 * confirmation.
 *
 * <p>{@link StatisticsSettingChange} lines are not deleted: the latest line per setting is its
 * current value, the line count is the revision the settings screen saves against, and the lines
 * show which rule applied when. Only the admin id and name are blanked after the period.
 *
 * <p>A non-positive period switches that part off instead of deleting everything.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsRetentionService {

  private final @NonNull ConsultantMessageStatRepository consultantMessageStatRepository;
  private final @NonNull StatisticsSettingChangeRepository statisticsSettingChangeRepository;
  private final @NonNull Clock clock;

  @Value("${statistics.message-count.retention.days:366}")
  private int messageCountRetentionDays;

  @Value("${statistics.setting-change.actor-retention.days:366}")
  private int settingChangeActorRetentionDays;

  @Value("${statistics.retention.batch-size:5000}")
  private int batchSize;

  /** Runs both parts; a failure in one is logged and retried on the next run, not rethrown. */
  public void purgeExpiredStatistics() {
    var now = LocalDateTime.now(clock);
    try {
      purgeMessageCounts(now);
    } catch (RuntimeException e) {
      log.error("Statistics retention could not delete expired message counts", e);
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
