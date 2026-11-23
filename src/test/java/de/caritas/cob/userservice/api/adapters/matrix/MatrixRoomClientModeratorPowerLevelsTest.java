package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ORISO-Frontend#2229: how the moderator-level sync reads a room's power levels. Levels follow
 * Synapse's accepted range, and the creators of a room version 12 room hold unlimited power and are
 * never written into {@code users}.
 */
class MatrixRoomClientModeratorPowerLevelsTest {

  private static final String USER_ID = "@user:matrix.example";

  private static Map<String, Object> basePowerLevels() {
    var content = new HashMap<String, Object>();
    content.put("users_default", 0);
    content.put("events", Map.of("m.room.power_levels", 100));
    content.put("state_default", 50);
    return content;
  }

  /** Synapse accepts integer levels beyond Java's int; reading one must not throw. */
  @Test
  void aPowerLevelBeyondTheIntRangeIsRead() {
    assertThat(MatrixRoomClient.powerLevel(3_000_000_000L, 0)).isEqualTo(3_000_000_000L);
    assertThat(MatrixRoomClient.powerLevel(" 75 ", 0)).isEqualTo(75);
    assertThat(MatrixRoomClient.powerLevel(null, 7)).isEqualTo(7);
    assertThatThrownBy(() -> MatrixRoomClient.powerLevel("high", 0))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> MatrixRoomClient.powerLevel(1.5, 0))
        .isInstanceOf(IllegalStateException.class);

    var power = new HashMap<String, Object>(Map.of("users_default", 3_000_000_000L));
    assertThat(MatrixRoomClient.moderatorPowerChanges(power, Map.of(USER_ID, true))).isEmpty();
  }

  /**
   * Room version 12: the creators hold unlimited power and Synapse refuses a power-levels event
   * that names them in {@code users}. Earlier versions have no such creators.
   */
  @Test
  void theCreatorsOfARoomVersion12RoomAreReadFromItsCreateEvent() {
    var v12 =
        java.util.List.of(
            Map.of(
                "type",
                "m.room.create",
                "state_key",
                "",
                "sender",
                "@creator:matrix.example",
                "content",
                Map.of(
                    "room_version", "12", "additional_creators", java.util.List.of(USER_ID, ""))));
    var v10 =
        java.util.List.of(
            Map.of(
                "type",
                "m.room.create",
                "state_key",
                "",
                "sender",
                "@creator:matrix.example",
                "content",
                Map.of("room_version", "10")));

    assertThat(MatrixRoomClient.unlimitedCreators(v12))
        .containsExactlyInAnyOrder("@creator:matrix.example", USER_ID);
    assertThat(MatrixRoomClient.unlimitedCreators(v10)).isEmpty();
    assertThat(MatrixRoomClient.unlimitedCreators(null)).isEmpty();
  }

  @Test
  void aCreatorWithUnlimitedPowerIsNeverWrittenIntoUsers() {
    var power = basePowerLevels();
    power.put("users", Map.of());

    var changes =
        MatrixRoomClient.moderatorPowerChanges(
            power,
            Map.of("@creator:matrix.example", true, USER_ID, true),
            java.util.Set.of("@creator:matrix.example"));

    assertThat(changes).containsExactly(Map.entry(USER_ID, 50));
  }
}
