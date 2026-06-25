package de.caritas.cob.userservice.api.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MatrixIdsTest {

  @Test
  void localpart_should_extract_username_from_valid_user_id() {
    assertThat(MatrixIds.localpart("@alice:matrix.oriso.org")).isEqualTo("alice");
  }

  @Test
  void localpart_should_extract_from_valid_room_id() {
    assertThat(MatrixIds.localpart("!abc123:matrix.oriso.org")).isEqualTo("abc123");
  }

  @Test
  void localpart_should_extract_from_id_with_ip_server() {
    assertThat(MatrixIds.localpart("@bob:127.0.0.1")).isEqualTo("bob");
  }

  @Test
  void localpart_should_throw_on_null_input() {
    assertThatThrownBy(() -> MatrixIds.localpart(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null or empty");
  }

  @Test
  void localpart_should_throw_on_empty_input() {
    assertThatThrownBy(() -> MatrixIds.localpart(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null or empty");
  }

  @Test
  void localpart_should_throw_on_input_with_no_colon() {
    assertThatThrownBy(() -> MatrixIds.localpart("@alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no colon separator");
  }

  @Test
  void isRoomId_should_return_true_for_room_id() {
    assertThat(MatrixIds.isRoomId("!abc123:matrix.oriso.org")).isTrue();
  }

  @Test
  void isRoomId_should_return_false_for_user_id() {
    assertThat(MatrixIds.isRoomId("@alice:matrix.oriso.org")).isFalse();
  }

  @Test
  void isRoomId_should_return_false_for_null() {
    assertThat(MatrixIds.isRoomId(null)).isFalse();
  }

  @Test
  void isUserId_should_return_true_for_user_id() {
    assertThat(MatrixIds.isUserId("@alice:matrix.oriso.org")).isTrue();
  }

  @Test
  void isUserId_should_return_false_for_room_id() {
    assertThat(MatrixIds.isUserId("!abc123:matrix.oriso.org")).isFalse();
  }

  @Test
  void isUserId_should_return_false_for_null() {
    assertThat(MatrixIds.isUserId(null)).isFalse();
  }

  @Test
  void constructor_should_throw() {
    assertThatThrownBy(MatrixIds::new).isInstanceOf(UnsupportedOperationException.class);
  }
}
