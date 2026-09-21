package de.caritas.cob.userservice.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * The wire form of the topic permission (ORISO-Admin#1026). Besides the three names the CSV import
 * sends plain yes/no values: {@code true} means today's behaviour ({@code CREATE}), {@code false}
 * the new strict default ({@code NONE}).
 */
class TopicPermissionTest {

  @Test
  void fromWire_readsTheThreeLevelsCaseInsensitively() {
    assertThat(TopicPermission.fromWire("NONE")).isEqualTo(TopicPermission.NONE);
    assertThat(TopicPermission.fromWire("select_existing"))
        .isEqualTo(TopicPermission.SELECT_EXISTING);
    assertThat(TopicPermission.fromWire(" Create ")).isEqualTo(TopicPermission.CREATE);
  }

  @Test
  void fromWire_mapsYesNoValuesFromTheCsvImport() {
    assertThat(TopicPermission.fromWire(true)).isEqualTo(TopicPermission.CREATE);
    assertThat(TopicPermission.fromWire(false)).isEqualTo(TopicPermission.NONE);
    assertThat(TopicPermission.fromWire("true")).isEqualTo(TopicPermission.CREATE);
    assertThat(TopicPermission.fromWire("FALSE")).isEqualTo(TopicPermission.NONE);
  }

  @Test
  void fromWire_leavesAMissingValueOpen() {
    assertThat(TopicPermission.fromWire(null)).isNull();
    assertThat(TopicPermission.fromWire("  ")).isNull();
  }

  @Test
  void fromWire_rejectsAnythingElseWith400() {
    assertThatThrownBy(() -> TopicPermission.fromWire("sometimes"))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> TopicPermission.fromWire(1)).isInstanceOf(BadRequestException.class);
  }
}
