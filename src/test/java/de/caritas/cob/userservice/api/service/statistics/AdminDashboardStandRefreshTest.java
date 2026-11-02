package de.caritas.cob.userservice.api.service.statistics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.GroupCountProjection;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStatisticsService.DashboardScope;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSnapshotInterval;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminDashboardStandRefreshTest {

  private final AdminDashboardStatisticsService dashboards =
      mock(AdminDashboardStatisticsService.class);
  private final AdminDashboardStandService stands = mock(AdminDashboardStandService.class);
  private final AdminDashboardStandWriter writer = mock(AdminDashboardStandWriter.class);
  private final AdminStatisticsRepository statistics = mock(AdminStatisticsRepository.class);
  private final StatisticsSettingsService settings = mock(StatisticsSettingsService.class);
  private final AdminDashboardStandRefresh refresh =
      new AdminDashboardStandRefresh(
          dashboards,
          stands,
          writer,
          statistics,
          settings,
          Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC));

  @Test
  void refreshAll_Should_GoOn_WithTheOtherScopes_WhenOneIntervalCannotBeRead() {
    for (var scope : DashboardScope.values()) {
      when(settings.snapshotInterval(scope.snapshotSetting()))
          .thenReturn(StatisticsSnapshotInterval.DAILY);
    }
    when(settings.snapshotInterval(DashboardScope.PLATFORM.snapshotSetting()))
        .thenThrow(new IllegalStateException("unreadable"));
    var tenant = tenant(7L);
    when(statistics.countConsultantsByTenant()).thenReturn(List.of(tenant));

    refresh.refreshAll();

    // the platform stand is skipped, the other scopes were still looked at, retention still ran
    verify(settings).snapshotInterval(DashboardScope.TENANT.snapshotSetting());
    verify(settings).snapshotInterval(DashboardScope.AGENCY.snapshotSetting());
    verify(stands)
        .currentStand(
            eq(DashboardScope.TENANT), eq(7L), eq(StatisticsSnapshotInterval.DAILY), any());
    verify(stands)
        .currentStand(
            eq(DashboardScope.AGENCY), eq(7L), eq(StatisticsSnapshotInterval.DAILY), any());
    verify(stands, never()).currentStand(eq(DashboardScope.PLATFORM), any(), any(), any());
    verify(writer).deleteComputedBefore(any());
  }

  private static GroupCountProjection tenant(long id) {
    var group = mock(GroupCountProjection.class);
    when(group.getGroupId()).thenReturn(id);
    return group;
  }
}
