package de.caritas.cob.userservice.api.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConsultantIdentityHasherTest {

  private ConsultantIdentityHasher newHasher(String secret) {
    var hasher = new ConsultantIdentityHasher();
    ReflectionTestUtils.setField(hasher, "secret", secret);
    ReflectionTestUtils.invokeMethod(hasher, "init");
    return hasher;
  }

  @Test
  void hashShouldBeDeterministicForSameConsultantIdAndSecret() {
    var hasher = newHasher("secret-a");

    assertThat(hasher.hash("consultant-1")).isEqualTo(hasher.hash("consultant-1"));
  }

  @Test
  void hashShouldDifferForDifferentConsultantIds() {
    var hasher = newHasher("secret-a");

    assertThat(hasher.hash("consultant-1")).isNotEqualTo(hasher.hash("consultant-2"));
  }

  @Test
  void hashShouldDifferAcrossSecrets_soTheSecretCannotBeGuessedFromOutputAlone() {
    var consultantId = "consultant-1";

    assertThat(newHasher("secret-a").hash(consultantId))
        .isNotEqualTo(newHasher("secret-b").hash(consultantId));
  }

  @Test
  void hashShouldNeverContainThePlainConsultantId() {
    var hasher = newHasher("secret-a");

    assertThat(hasher.hash("consultant-1")).doesNotContain("consultant-1");
  }

  @Test
  void initShouldFailFastWhenSecretIsBlank() {
    var hasher = new ConsultantIdentityHasher();
    ReflectionTestUtils.setField(hasher, "secret", "");

    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(hasher, "init"))
        .isInstanceOf(IllegalStateException.class);
  }
}
