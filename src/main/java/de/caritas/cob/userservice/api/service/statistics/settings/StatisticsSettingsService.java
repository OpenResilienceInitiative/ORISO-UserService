package de.caritas.cob.userservice.api.service.statistics.settings;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.StatisticsSettingChange;
import de.caritas.cob.userservice.api.port.out.StatisticsSettingChangeRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Global statistics settings (platform admins only) and their change protocol. A change applies
 * from the moment it is saved; past statistics are never recomputed. Every change is kept with old
 * value, new value, actor and time, so it stays visible which rule applied when.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatisticsSettingsService {

  private final @NonNull StatisticsSettingChangeRepository repository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull Clock clock;

  public record SettingState(
      String key,
      String value,
      String defaultValue,
      List<String> options,
      String effectiveFrom,
      String changedBy) {}

  public record StatisticsSettings(List<SettingState> settings, long revision) {}

  public record SettingChangeEntry(
      String key,
      String oldValue,
      String newValue,
      String changedById,
      String changedByName,
      String effectiveFrom) {}

  public StatisticsSettings getSettings() {
    requirePlatformAdmin();
    return currentSettings();
  }

  /**
   * Saves the given values. Only values that differ from the current one produce a protocol line.
   * The revision is the number of protocol lines the caller saw; a different number means someone
   * else saved in between, and nothing is written.
   */
  @Transactional
  public StatisticsSettings updateSettings(Map<String, String> values, Long revision) {
    requirePlatformAdmin();
    if (values == null || values.isEmpty()) {
      throw new BadRequestException("No statistics settings given");
    }
    if (revision == null) {
      throw new BadRequestException("The revision of the statistics settings is missing");
    }
    values.forEach(StatisticsSettingsService::validate);
    if (revision != repository.count()) {
      throw new ConflictException("Statistics settings were changed in the meantime");
    }

    var now = LocalDateTime.now(clock);
    for (var setting : StatisticsSetting.values()) {
      var newValue = values.get(setting.key());
      if (newValue == null) {
        continue;
      }
      var oldValue = currentValue(setting);
      if (newValue.equals(oldValue)) {
        continue;
      }
      repository.save(
          StatisticsSettingChange.builder()
              .settingKey(setting.key())
              .oldValue(oldValue)
              .newValue(newValue)
              .changedById(authenticatedUser.getUserId())
              .changedByName(authenticatedUser.getUsername())
              .effectiveFrom(now)
              .build());
    }
    return currentSettings();
  }

  public List<SettingChangeEntry> getHistory() {
    requirePlatformAdmin();
    return repository.findAllByOrderByIdAsc().stream()
        .map(
            change ->
                new SettingChangeEntry(
                    change.getSettingKey(),
                    change.getOldValue(),
                    change.getNewValue(),
                    change.getChangedById(),
                    change.getChangedByName(),
                    format(change.getEffectiveFrom())))
        .toList();
  }

  /**
   * The small-cell threshold for an aggregated view. No authorization: called while building the
   * dashboard. Falls back to the default when the store cannot be read.
   */
  public int minimumCounsellors(StatisticsSetting setting) {
    return Integer.parseInt(currentValue(setting));
  }

  public boolean isMessageCountTrackingEnabled() {
    return StatisticsSetting.ENABLED.equals(currentValue(StatisticsSetting.MESSAGE_COUNT_TRACKING));
  }

  private StatisticsSettings currentSettings() {
    var states =
        Arrays.stream(StatisticsSetting.values())
            .map(
                setting -> {
                  var latest = latest(setting);
                  return new SettingState(
                      setting.key(),
                      latest
                          .map(StatisticsSettingChange::getNewValue)
                          .orElse(setting.defaultValue()),
                      setting.defaultValue(),
                      setting.options(),
                      latest.map(change -> format(change.getEffectiveFrom())).orElse(null),
                      latest.map(StatisticsSettingChange::getChangedByName).orElse(null));
                })
            .toList();
    return new StatisticsSettings(states, repository.count());
  }

  private String currentValue(StatisticsSetting setting) {
    try {
      return latest(setting)
          .map(StatisticsSettingChange::getNewValue)
          .filter(value -> setting.options().contains(value))
          .orElse(setting.defaultValue());
    } catch (RuntimeException e) {
      log.error("Could not read statistics setting {}, using its default", setting.key(), e);
      return setting.defaultValue();
    }
  }

  private Optional<StatisticsSettingChange> latest(StatisticsSetting setting) {
    return repository.findFirstBySettingKeyOrderByIdDesc(setting.key());
  }

  private static void validate(String key, String value) {
    var setting =
        StatisticsSetting.byKey(key)
            .orElseThrow(() -> new BadRequestException("Unknown statistics setting " + key));
    if (!setting.options().contains(value)) {
      throw new BadRequestException("Invalid value for statistics setting " + key);
    }
  }

  private void requirePlatformAdmin() {
    if (!authenticatedUser.isPlatformAdmin()) {
      throw new ForbiddenException(
          "User %s may not access the statistics settings"
              .formatted(authenticatedUser.getUserId()));
    }
  }

  private static String format(LocalDateTime dateTime) {
    return dateTime == null ? null : dateTime.toString();
  }
}
