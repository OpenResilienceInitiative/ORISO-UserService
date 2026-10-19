package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.web.dto.NotificationsSettingsDTO;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the notification-settings merge against the one bug it can actually have: a switch that is
 * added to the API spec and forgotten in the merge.
 *
 * <p>That failure is silent — the switch renders, the user flips it, the save succeeds, and the
 * value is dropped. So the check walks the generated DTO by reflection rather than listing the
 * fields a second time, which would have the same problem.
 */
class NotificationsSettingsMergeTest {

  private static List<String> booleanSettingNames() {
    return Arrays.stream(NotificationsSettingsDTO.class.getMethods())
        .map(Method::getName)
        .filter(name -> name.startsWith("set") && name.endsWith("Enabled"))
        .map(name -> name.substring(3))
        .sorted()
        .collect(Collectors.toList());
  }

  @Test
  void everySwitchInTheApiSpecIsMerged() {
    List<String> inSpec = booleanSettingNames();

    // Each entry writes exactly one field. Which one is found by writing
    // opposite values into two fresh objects and seeing where they differ —
    // not by writing `true` and looking for it, because the switches do not
    // all default to the same value.
    List<String> merged =
        UserServiceMapper.NOTIFICATION_SETTINGS.stream()
            .map(
                setting -> {
                  NotificationsSettingsDTO on = new NotificationsSettingsDTO();
                  NotificationsSettingsDTO off = new NotificationsSettingsDTO();
                  setting.write().accept(on, true);
                  setting.write().accept(off, false);
                  return inSpec.stream()
                      .filter(name -> !java.util.Objects.equals(read(on, name), read(off, name)))
                      .findFirst()
                      .orElse("<none>");
                })
            .sorted()
            .collect(Collectors.toList());

    assertThat(merged)
        .as(
            "every boolean switch on NotificationsSettingsDTO has to be merged, "
                + "or flipping it in the profile is silently dropped")
        .containsExactlyElementsOf(inSpec);
  }

  @Test
  void everySwitchIsReadBackAsItWasWritten() {
    for (var setting : UserServiceMapper.NOTIFICATION_SETTINGS) {
      NotificationsSettingsDTO dto = new NotificationsSettingsDTO();
      setting.write().accept(dto, true);
      assertThat(setting.read().apply(dto)).isTrue();
      setting.write().accept(dto, false);
      assertThat(setting.read().apply(dto)).isFalse();
    }
  }

  @Test
  void carriesTheOccasionsTheNotificationMatrixNeeds() {
    // ADR-019: the counsellor list needs an assignment and a feedback switch,
    // and both roles need one for planned platform notices.
    assertThat(booleanSettingNames())
        .contains(
            "AssignmentNotificationEnabled",
            "FeedbackNotificationEnabled",
            "ServiceNoticeNotificationEnabled");
  }

  private static Boolean read(NotificationsSettingsDTO dto, String name) {
    for (String prefix : List.of("get", "is")) {
      try {
        Object value = NotificationsSettingsDTO.class.getMethod(prefix + name).invoke(dto);
        return (Boolean) value;
      } catch (ReflectiveOperationException ignored) {
        // try the other accessor prefix
      }
    }
    throw new IllegalStateException("no accessor for " + name);
  }
}
