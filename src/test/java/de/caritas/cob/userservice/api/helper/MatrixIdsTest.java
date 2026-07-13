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
  void localpartLenient_should_extract_username_from_valid_user_id() {
    assertThat(MatrixIds.localpartLenient("@alice:matrix.oriso.org")).isEqualTo("alice");
  }

  @Test
  void localpartLenient_should_strip_sigil_when_no_colon_present() {
    assertThat(MatrixIds.localpartLenient("@alice")).isEqualTo("alice");
  }

  @Test
  void localpartLenient_should_return_input_when_no_sigil_and_no_colon() {
    assertThat(MatrixIds.localpartLenient("alice")).isEqualTo("alice");
  }

  @Test
  void localpartLenient_should_split_on_colon_even_without_sigil() {
    assertThat(MatrixIds.localpartLenient("alice:matrix.oriso.org")).isEqualTo("alice");
  }

  @Test
  void localpartLenient_should_return_null_unchanged() {
    assertThat(MatrixIds.localpartLenient(null)).isNull();
  }

  @Test
  void localpartLenient_should_return_blank_unchanged() {
    assertThat(MatrixIds.localpartLenient("   ")).isEqualTo("   ");
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
  void constructor_should_throw() throws Exception {
    var c = MatrixIds.class.getDeclaredConstructor();
    c.setAccessible(true);
    assertThatThrownBy(c::newInstance)
        .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
        .hasCauseInstanceOf(UnsupportedOperationException.class);
  }

  // ---------------------------------------------------------------------------
  // ADR-005 / DB-M04 regression: the server part of a Matrix ID is opaque to
  // MatrixIds. Parsing must stay correct whether the server is a DNS name
  // (matrix.oriso.org), carries a port, or is a bare IPv4. These lock the CURRENT
  // behaviour (documenting, not inventing) so the former bare-Hetzner-IP class of
  // Matrix ID can never silently slip back in unnoticed.
  // ---------------------------------------------------------------------------

  @Test
  void localpart_should_extract_when_server_has_dots_and_port() {
    // Only the first colon separates the local part; dots and the port colon are ignored.
    assertThat(MatrixIds.localpart("@alice:matrix.oriso.org:8448")).isEqualTo("alice");
  }

  @Test
  void localpart_should_extract_from_room_id_with_dotted_server_and_port() {
    assertThat(MatrixIds.localpart("!abc123:matrix.oriso.org:8448")).isEqualTo("abc123");
  }

  @Test
  void localpart_should_extract_when_server_is_bare_ipv4() {
    // Documents current behaviour: the IPv4 lives only in the (ignored) server part.
    assertThat(MatrixIds.localpart("@alice:91.99.219.182")).isEqualTo("alice");
  }

  @Test
  void localpart_should_extract_when_server_is_bare_ipv4_with_port() {
    assertThat(MatrixIds.localpart("@alice:91.99.219.182:8008")).isEqualTo("alice");
  }

  @Test
  void localpart_should_return_empty_when_localpart_is_missing() {
    // Documents current behaviour: "@:server" has its colon at index 1, so substring(1, 1) == "".
    assertThat(MatrixIds.localpart("@:matrix.oriso.org")).isEmpty();
  }

  @Test
  void localpart_should_extract_when_server_part_is_empty() {
    assertThat(MatrixIds.localpart("@alice:")).isEqualTo("alice");
  }

  @Test
  void localpart_should_throw_when_colon_is_first_char() {
    assertThatThrownBy(() -> MatrixIds.localpart(":matrix.oriso.org"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no colon separator");
  }

  @Test
  void localpartLenient_should_extract_when_server_has_dots_and_port() {
    assertThat(MatrixIds.localpartLenient("@alice:matrix.oriso.org:8448")).isEqualTo("alice");
  }

  @Test
  void localpartLenient_should_extract_when_server_is_bare_ipv4() {
    assertThat(MatrixIds.localpartLenient("@bob:91.99.219.182")).isEqualTo("bob");
  }

  @Test
  void localpartLenient_should_return_server_part_when_localpart_is_missing() {
    // Documents current behaviour: after stripping "@", ":server" has its colon at index 0, so the
    // lenient parser returns the remainder unchanged instead of throwing.
    assertThat(MatrixIds.localpartLenient("@:matrix.oriso.org")).isEqualTo(":matrix.oriso.org");
  }

  @Test
  void isUserId_should_return_false_for_empty_string() {
    assertThat(MatrixIds.isUserId("")).isFalse();
  }

  @Test
  void isRoomId_should_return_false_for_empty_string() {
    assertThat(MatrixIds.isRoomId("")).isFalse();
  }

  @Test
  void isUserId_should_detect_sigil_independently_of_ipv4_server() {
    // Sigil detection must not depend on the server part being a DNS name rather than an IP.
    assertThat(MatrixIds.isUserId("@alice:91.99.219.182")).isTrue();
  }

  @Test
  void isRoomId_should_detect_sigil_independently_of_ipv4_server() {
    assertThat(MatrixIds.isRoomId("!abc123:91.99.219.182")).isTrue();
  }
}
