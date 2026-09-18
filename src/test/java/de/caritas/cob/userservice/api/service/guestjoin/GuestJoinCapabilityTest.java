package de.caritas.cob.userservice.api.service.guestjoin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GuestJoinCapabilityTest {
  private static final String KEY =
      Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

  @Test
  void replayAfterReconstructionKeepsPasswordButSeparatesProviderAndCandidate() {
    var first = GuestJoinCapability.parse(KEY);
    var replay = GuestJoinCapability.parse(KEY);

    assertThat(first.identityPassword("biene_rayan_1234"))
        .isEqualTo(replay.identityPassword("biene_rayan_1234"))
        .isNotEqualTo(first.chatPassword("biene_rayan_1234"))
        .isNotEqualTo(first.identityPassword("biene_rayan_1235"))
        .containsPattern("[A-Z]")
        .containsPattern("[a-z]")
        .containsPattern("[0-9]")
        .contains("!");
    assertThat(first.attemptHash())
        .isEqualTo("66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925")
        .doesNotContain(KEY);
    assertThat(first.toString())
        .doesNotContain(KEY)
        .doesNotContain(first.identityPassword("biene_rayan_1234"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "short",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+"
      })
  void rejectsNonCanonicalOrWrongLengthRetryKeys(String key) {
    assertThatThrownBy(() -> GuestJoinCapability.parse(key))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("Invalid guest Join retry key");
  }
}
