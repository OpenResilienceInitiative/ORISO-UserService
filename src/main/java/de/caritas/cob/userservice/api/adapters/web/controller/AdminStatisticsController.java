package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStatisticsService;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStatisticsService.DashboardStatistics;
import io.swagger.annotations.Api;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves aggregated counselling statistics for the admin dashboard.
 *
 * <p>Data source is exclusively the application-layer database (KDG compliance). The response is
 * scoped to the caller: platform admins receive per-tenant rows, tenant admins receive their tenant
 * plus per-agency rows, agency admins receive only their own agencies.
 */
@RestController
@RequiredArgsConstructor
@Api(tags = "admin-statistics-controller")
public class AdminStatisticsController {

  private final @NonNull AdminDashboardStatisticsService adminDashboardStatisticsService;

  @GetMapping({"/useradmin/statistics/dashboard", "/service/useradmin/statistics/dashboard"})
  public ResponseEntity<DashboardStatistics> getDashboardStatistics() {
    return ResponseEntity.ok(adminDashboardStatisticsService.buildDashboard());
  }
}
