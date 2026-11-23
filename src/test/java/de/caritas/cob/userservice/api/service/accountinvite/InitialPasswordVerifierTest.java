package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InitialPasswordVerifierTest {
  private final InitialPasswordVerifier verifier = new InitialPasswordVerifier();

  @Test
  void saltsEachLinkAndChecksTheCompleteLongUnicodeCredential() {
    String prefixBeyondBcryptLimit = "🔐".repeat(24);
    String original = prefixBeyondBcryptLimit + "first-ending";
    String different = prefixBeyondBcryptLimit + "other-ending";

    String first = verifier.encode(original);
    String second = verifier.encode(original);

    assertThat(first).isNotEqualTo(second).doesNotContain(original);
    assertThat(verifier.matches(original, first)).isTrue();
    assertThat(verifier.matches(different, first)).isFalse();
  }
}
