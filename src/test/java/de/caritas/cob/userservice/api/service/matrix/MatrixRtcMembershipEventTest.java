package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatrixRtcMembershipEventTest {
  private Map<String, Object> content() {
    return new HashMap<>(
        Map.of(
            "application",
            "m.call",
            "scope",
            "m.room",
            "call_id",
            "",
            "device_id",
            "DEVICE",
            "focus_active",
            Map.of("type", "livekit"),
            "foci_preferred",
            List.of()));
  }

  private Map<String, Object> event(Map<String, Object> content) {
    return Map.of(
        "type",
        "org.matrix.msc3401.call.member",
        "event_id",
        "$member",
        "sender",
        "@user:example",
        "state_key",
        "_@user:example_DEVICE_m.call",
        "origin_server_ts",
        1000L,
        "content",
        content);
  }

  @Test
  void readsRoomScopedFlatMembershipWithoutMistakingItsEmptyCallIdForTheOrisoCallId() {
    var content = content();
    content.put("expires", 60000L);
    var member = MatrixRtcMembershipEvent.parse(event(content)).orElseThrow();
    assertThat(member.sender()).isEqualTo("@user:example");
    assertThat(member.stateKey()).isEqualTo("_@user:example_DEVICE_m.call");
    assertThat(member.deviceId()).isEqualTo("DEVICE");
    assertThat(member.expiresAt()).isEqualTo(61000L);
    assertThat(member.departed()).isFalse();
  }

  @Test
  void usesCreationTimeAndSdkDefaultFourHourDuration() {
    var content = content();
    content.put("created_ts", 500L);
    assertThat(MatrixRtcMembershipEvent.parse(event(content)).orElseThrow().expiresAt())
        .isEqualTo(500L + 4 * 60 * 60 * 1000L);
  }

  @Test
  void representsEmptyStateAsDepartureOfTheExactStateKeyNotEveryDeviceOfTheSender() {
    var member = MatrixRtcMembershipEvent.parse(event(Map.of())).orElseThrow();
    assertThat(member.departed()).isTrue();
    assertThat(member.deviceId()).isNull();
    assertThat(member.stateKey()).isEqualTo("_@user:example_DEVICE_m.call");
  }

  @Test
  void ignoresLegacyOrDifferentApplicationMemberships() {
    assertThat(MatrixRtcMembershipEvent.parse(event(Map.of("memberships", List.of(content())))))
        .isEmpty();
    var content = content();
    content.put("application", "unrelated.application");
    assertThat(MatrixRtcMembershipEvent.parse(event(content))).isEmpty();
    content = content();
    content.put("call_id", "another-session");
    assertThat(MatrixRtcMembershipEvent.parse(event(content))).isEmpty();
  }

  @Test
  void rejectsMalformedDurationsAndOverflowRatherThanInventingAttendance() {
    for (Object invalid : List.of(-1L, 1.5, "60000", Long.MAX_VALUE)) {
      var content = content();
      content.put("expires", invalid);
      assertThat(MatrixRtcMembershipEvent.parse(event(content))).isEmpty();
    }
  }
}
