package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService.SettingChangeEntry;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService.StatisticsSettings;
import io.swagger.annotations.Api;
import java.util.List;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global statistics settings and their change protocol (OBP #93). Platform admins only: the
 * security config gates the paths, the service checks again.
 */
@RestController
@RequiredArgsConstructor
@Api(tags = "statistics-settings-controller")
@RequestMapping({"/useradmin/statistics/settings", "/service/useradmin/statistics/settings"})
public class StatisticsSettingsController {

  private final @NonNull StatisticsSettingsService statisticsSettingsService;

  public record UpdateStatisticsSettingsRequest(Map<String, String> values, Long revision) {}

  @GetMapping
  public ResponseEntity<StatisticsSettings> getSettings() {
    return ResponseEntity.ok(statisticsSettingsService.getSettings());
  }

  @PutMapping
  public ResponseEntity<StatisticsSettings> updateSettings(
      @RequestBody UpdateStatisticsSettingsRequest request) {
    return ResponseEntity.ok(
        statisticsSettingsService.updateSettings(request.values(), request.revision()));
  }

  @GetMapping("/history")
  public ResponseEntity<List<SettingChangeEntry>> getHistory() {
    return ResponseEntity.ok(statisticsSettingsService.getHistory());
  }
}
