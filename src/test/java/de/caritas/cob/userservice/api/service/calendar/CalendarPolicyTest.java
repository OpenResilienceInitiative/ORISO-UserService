package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CalendarPolicyTest {

  @Test
  void fromPolicies_Should_ReadScopeAndUsernames() {
    var policy =
        CalendarPolicy.fromPolicies(
            Map.of(
                "calendar",
                Map.of(
                    "visibilityScope",
                    Map.of("value", "PLATFORM", "mode", "REQUIRED", "inherited", true),
                    "showAskerUsernames",
                    Map.of("value", true, "mode", "RECOMMENDED", "inherited", false))));

    assertThat(policy.visibilityScope()).isEqualTo(CalendarVisibilityScope.PLATFORM);
    assertThat(policy.showAskerUsernames()).isTrue();
  }

  @Test
  void fromPolicies_Should_FailClosed_When_TheSettingIsMissingOrUnreadable() {
    assertThat(CalendarPolicy.fromPolicies(null)).isEqualTo(CalendarPolicy.CLOSED);
    assertThat(CalendarPolicy.fromPolicies(Map.of())).isEqualTo(CalendarPolicy.CLOSED);
    assertThat(CalendarPolicy.fromPolicies(Map.of("calendar", "yes")))
        .isEqualTo(CalendarPolicy.CLOSED);
    assertThat(
            CalendarPolicy.fromPolicies(
                Map.of(
                    "calendar",
                    Map.of(
                        "visibilityScope",
                        Map.of("value", "EVERYONE"),
                        "showAskerUsernames",
                        Map.of("value", "true")))))
        .isEqualTo(CalendarPolicy.CLOSED);
  }

  @Test
  void fromPolicies_Should_ReadEachFieldOnItsOwn() {
    var onlyScope =
        CalendarPolicy.fromPolicies(
            Map.of("calendar", Map.of("visibilityScope", Map.of("value", "TENANT"))));
    var onlyUsernames =
        CalendarPolicy.fromPolicies(
            Map.of("calendar", Map.of("showAskerUsernames", Map.of("value", true))));

    assertThat(onlyScope).isEqualTo(new CalendarPolicy(CalendarVisibilityScope.TENANT, false));
    assertThat(onlyUsernames).isEqualTo(new CalendarPolicy(CalendarVisibilityScope.AGENCY, true));
  }
}
