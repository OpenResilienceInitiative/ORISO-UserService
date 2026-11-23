package de.caritas.cob.userservice.api.service.calendar;

import java.util.Map;

/**
 * The calendar settings of one Träger as the TenantService resolved them (ADR-032).
 *
 * @param visibilityScope whose calendar a counsellor may look at
 * @param showAskerUsernames whether a colleague sees the asker's username on an appointment
 */
public record CalendarPolicy(CalendarVisibilityScope visibilityScope, boolean showAskerUsernames) {

  /** The answer for a missing or unreadable setting: the narrowest scope, no usernames. */
  public static final CalendarPolicy CLOSED =
      new CalendarPolicy(CalendarVisibilityScope.AGENCY, false);

  private static final String SETTING = "calendar";
  private static final String SCOPE = "visibilityScope";
  private static final String USERNAMES = "showAskerUsernames";
  private static final String VALUE = "value";

  /**
   * Reads {@code calendar.visibilityScope.value} and {@code calendar.showAskerUsernames.value} from
   * the raw {@code GET /tenantadmin/{id}/permission-policies} body. Leniently on purpose: a
   * TenantService that does not know the setting yet, a missing field or an unknown value all read
   * as the closed answer for that field.
   */
  public static CalendarPolicy fromPolicies(Map<String, Object> policies) {
    if (policies == null || !(policies.get(SETTING) instanceof Map<?, ?> setting)) {
      return CLOSED;
    }
    var scope = CalendarVisibilityScope.AGENCY;
    if (setting.get(SCOPE) instanceof Map<?, ?> resolved
        && resolved.get(VALUE) instanceof String value) {
      for (var candidate : CalendarVisibilityScope.values()) {
        if (candidate.name().equals(value)) {
          scope = candidate;
        }
      }
    }
    var usernames =
        setting.get(USERNAMES) instanceof Map<?, ?> resolved
            && Boolean.TRUE.equals(resolved.get(VALUE));
    return new CalendarPolicy(scope, usernames);
  }
}
