package de.caritas.cob.userservice.api.workflow.statistics;

import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStandRefresh;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSnapshotInterval;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Calculates the statistics stands right after their cutoff, on one replica at a time through the
 * scheduled-task lease (UserService#1547). It runs every hour, so a replica that was down at 06:00
 * catches up; a run finding every stand in place calculates nothing.
 */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
public class AdminDashboardSnapshotScheduler {

  public static final String TASK_NAME = "statistics-dashboard-snapshot";
  private static final Duration CLAIM = Duration.ofMinutes(30);

  private final @NonNull AdminDashboardStandRefresh refresh;
  private final @NonNull ScheduledTaskClaimService claims;

  @Scheduled(cron = "${statistics.snapshot.cron}", zone = StatisticsSnapshotInterval.CUTOFF_ZONE)
  public void refreshStands() {
    var lease = claims.tryClaimLease(TASK_NAME, CLAIM);
    if (lease.isEmpty()) {
      return;
    }
    try {
      refresh.refreshAll();
    } catch (RuntimeException failure) {
      log.warn("Statistics stand refresh failed; retrying next run ({})", failure.getMessage());
    } finally {
      try {
        claims.release(lease.get());
      } catch (RuntimeException failure) {
        log.warn(
            "Could not release statistics stand claim ({})", failure.getClass().getSimpleName());
      }
    }
  }
}
