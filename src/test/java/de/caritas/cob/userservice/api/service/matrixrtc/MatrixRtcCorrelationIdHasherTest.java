package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MatrixRtcCorrelationIdHasherTest {

  private static final String ROOM_ID = "!source:matrix.oriso.org";
  private static final String MATRIX_USER_ID = "@participant:matrix.oriso.org";

  private MatrixRtcCorrelationIdHasher newHasher(String secret) {
    var hasher = new MatrixRtcCorrelationIdHasher();
    ReflectionTestUtils.setField(hasher, "secret", secret);
    ReflectionTestUtils.invokeMethod(hasher, "init");
    return hasher;
  }

  @Test
  void correlationIdShouldBeDeterministicForSameRoomAndUserIdAndSecret() {
    var hasher = newHasher("secret-a");

    assertThat(hasher.correlationId(ROOM_ID, MATRIX_USER_ID))
        .isEqualTo(hasher.correlationId(ROOM_ID, MATRIX_USER_ID));
  }

  @Test
  void correlationIdShouldDifferForDifferentRoomOrUserIds() {
    var hasher = newHasher("secret-a");

    assertThat(hasher.correlationId(ROOM_ID, MATRIX_USER_ID))
        .isNotEqualTo(hasher.correlationId(ROOM_ID, "@someone-else:matrix.oriso.org"))
        .isNotEqualTo(hasher.correlationId("!other:matrix.oriso.org", MATRIX_USER_ID));
  }

  @Test
  void correlationIdShouldDifferAcrossSecrets_soACandidatePairCannotBeConfirmedWithoutTheSecret() {
    assertThat(newHasher("secret-a").correlationId(ROOM_ID, MATRIX_USER_ID))
        .isNotEqualTo(newHasher("secret-b").correlationId(ROOM_ID, MATRIX_USER_ID));
  }

  @Test
  void correlationIdShouldNeverContainTheRawRoomOrUserId() {
    var hasher = newHasher("secret-a");

    assertThat(hasher.correlationId(ROOM_ID, MATRIX_USER_ID))
        .doesNotContain(ROOM_ID)
        .doesNotContain(MATRIX_USER_ID);
  }

  @Test
  void initShouldFailFastWhenSecretIsBlank() {
    var hasher = new MatrixRtcCorrelationIdHasher();
    ReflectionTestUtils.setField(hasher, "secret", "");

    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(hasher, "init"))
        .isInstanceOf(IllegalStateException.class);
  }
}
