package de.caritas.cob.userservice.api.service.statistics.settings;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The statistics settings a platform admin can change. Each value is one of a fixed set of options;
 * a change applies from the moment it is saved and never recomputes the past.
 *
 * <p>The small-cell thresholds start at 2: a single counsellor behind a number would identify them
 * directly. They only apply to aggregated views (agency, tenant, platform) - never to a
 * counsellor's own statistics.
 */
public enum StatisticsSetting {
  AGENCY_MINIMUM_COUNSELLORS("agencyMinimumCounsellors", "5", Options.THRESHOLDS),
  TENANT_MINIMUM_COUNSELLORS("tenantMinimumCounsellors", "5", Options.THRESHOLDS),
  PLATFORM_MINIMUM_COUNSELLORS("platformMinimumCounsellors", "5", Options.THRESHOLDS),
  MESSAGE_COUNT_TRACKING("messageCountTracking", "ENABLED", Options.SWITCH);

  public static final String ENABLED = "ENABLED";

  private final String key;
  private final String defaultValue;
  private final List<String> options;

  StatisticsSetting(String key, String defaultValue, List<String> options) {
    this.key = key;
    this.defaultValue = defaultValue;
    this.options = options;
  }

  public String key() {
    return key;
  }

  public String defaultValue() {
    return defaultValue;
  }

  public List<String> options() {
    return options;
  }

  public static Optional<StatisticsSetting> byKey(String key) {
    return Arrays.stream(values()).filter(setting -> setting.key.equals(key)).findFirst();
  }

  private static final class Options {
    private static final List<String> THRESHOLDS =
        List.of("2", "3", "4", "5", "6", "7", "8", "9", "10");
    private static final List<String> SWITCH = List.of("ENABLED", "DISABLED");
  }
}
