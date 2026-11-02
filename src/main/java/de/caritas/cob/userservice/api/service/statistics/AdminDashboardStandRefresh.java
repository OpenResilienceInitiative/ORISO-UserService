package de.caritas.cob.userservice.api.service.statistics;

import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.GroupCountProjection;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStatisticsService.DashboardScope;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSnapshotInterval;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Calculates every missing stand for its role and tenant. A tenant without counsellors has nothing
 * to calculate here; its first dashboard request creates the stand.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDashboardStandRefresh {

  // A stand is replaced at the latest after a week; older rows belong to removed tenants.
  private static final Duration RETENTION = Duration.ofDays(14);

  private final @NonNull AdminDashboardStatisticsService dashboards;
  private final @NonNull AdminDashboardStandService stands;
  private final @NonNull AdminDashboardStandWriter writer;
  private final @NonNull AdminStatisticsRepository statistics;
  private final @NonNull StatisticsSettingsService settings;
  private final @NonNull Clock clock;

  public void refreshAll() {
    for (var scope : DashboardScope.values()) {
      var interval = settings.snapshotInterval(scope.snapshotSetting());
      if (interval == StatisticsSnapshotInterval.LIVE) {
        writer.deleteScope(scope.name());
        continue;
      }
      tenantsOf(scope).forEach(tenantId -> refresh(scope, tenantId, interval));
    }
    writer.deleteComputedBefore(LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).minus(RETENTION));
  }

  private void refresh(DashboardScope scope, Long tenantId, StatisticsSnapshotInterval interval) {
    try {
      stands.currentStand(
          scope,
          tenantId,
          interval,
          () -> dashboards.calculateDashboard(scope, tenantId, interval));
    } catch (RuntimeException failure) {
      log.warn(
          "No {} statistics stand for tenant {} in this run ({})",
          scope,
          tenantId,
          failure.getClass().getSimpleName());
    }
  }

  private List<Long> tenantsOf(DashboardScope scope) {
    if (scope == DashboardScope.PLATFORM) {
      return List.of(AdminDashboardStatisticsService.PLATFORM_TENANT_ID);
    }
    return statistics.countConsultantsByTenant().stream()
        .map(GroupCountProjection::getGroupId)
        .filter(Objects::nonNull)
        .toList();
  }
}
