package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.statistics.ConsultantStatisticsService;
import de.caritas.cob.userservice.api.service.statistics.ConsultantStatisticsService.ConsultantStatistics;
import io.swagger.annotations.Api;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the statistics shown on the consultant's own profile.
 *
 * <p>Data source is exclusively the application-layer database (KDG compliance) and the response is
 * always scoped to the authenticated consultant.
 */
@RestController
@RequiredArgsConstructor
@Api(tags = "consultant-statistics-controller")
public class ConsultantStatisticsController {

  private final @NonNull ConsultantStatisticsService consultantStatisticsService;

  @GetMapping({"/users/statistics/consultant", "/service/users/statistics/consultant"})
  public ResponseEntity<ConsultantStatistics> getConsultantStatistics(
      @RequestParam String startDate, @RequestParam String endDate) {
    return ResponseEntity.ok(consultantStatisticsService.buildStatistics(startDate, endDate));
  }
}
