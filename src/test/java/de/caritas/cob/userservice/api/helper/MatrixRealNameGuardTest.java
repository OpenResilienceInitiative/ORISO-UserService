package de.caritas.cob.userservice.api.helper;

import static de.caritas.cob.userservice.api.helper.MatrixRealNameGuard.assertNoRealNameReachedMatrix;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The guard is load-bearing for #1200, so it is itself tested: a guard that silently stops biting
 * would leave every call site unprotected while all the tests stayed green. The leaking variants
 * below are the ones an adversarial review of PR #1202 got past the first, equality-based version.
 */
class MatrixRealNameGuardTest {

  private static final String FIRST = "Angela";
  private static final String LAST = "Musterfrau";

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Angela Musterfrau", // plain
        "Musterfrau Angela", // reversed
        "Angela Maria Musterfrau", // inserted middle name
        "Angela  Musterfrau", // doubled space
        "  Angela Musterfrau  ", // leading/trailing whitespace
        "angela musterfrau", // different capitalisation
        "ANGELA MUSTERFRAU",
        "Beraterin Angela Musterfrau", // embedded in a longer string
        "Angela Musterfrau (Caritas)",
        "Musterfrau, Angela",
        "Beratung wird von Angela Musterfrau fortgefuehrt." // inside sentence copy
      })
  @DisplayName("catches every spelling of the real name in a plain argument")
  void assertNoRealNameReachedMatrix_Should_Fail_ForEveryVariant(String leakingValue) {
    var matrixUserClient = mock(MatrixUserClient.class);
    matrixUserClient.updateUserDisplayName("@beraterin1:matrix.oriso.org", leakingValue);

    assertThatThrownBy(() -> assertNoRealNameReachedMatrix(matrixUserClient, FIRST, LAST))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("#1200");
  }

  @Test
  @DisplayName("catches the name nested inside a serialized payload")
  void assertNoRealNameReachedMatrix_Should_Fail_When_TheNameIsNestedInAMap() throws Exception {
    var matrixUserClient = mock(MatrixUserClient.class);
    var payload = Map.of("type", "CASE_HANDOVER_GRANTED", "username", "Angela Musterfrau");
    matrixUserClient.createUserId("beraterin1", "pw", String.valueOf(List.of(payload)));

    assertThatThrownBy(() -> assertNoRealNameReachedMatrix(matrixUserClient, FIRST, LAST))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("catches the name inside a JSON message body")
  void assertNoRealNameReachedMatrix_Should_Fail_When_TheNameIsInAJsonBody() throws Exception {
    var matrixUserClient = mock(MatrixUserClient.class);
    matrixUserClient.createUserId(
        "beraterin1",
        "pw",
        "[SYSTEM_NOTIFICATION]{\"type\":\"CASE_HANDOVER_GRANTED\","
            + "\"username\":\"Angela Musterfrau\"}");

    assertThatThrownBy(() -> assertNoRealNameReachedMatrix(matrixUserClient, FIRST, LAST))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("passes for the pseudonym and the username, which are the permitted values")
  void assertNoRealNameReachedMatrix_Should_Pass_ForPermittedValues() throws Exception {
    var matrixUserClient = mock(MatrixUserClient.class);
    matrixUserClient.updateUserDisplayName("@beraterin1:matrix.oriso.org", "Frau M.");
    matrixUserClient.createUserId("beraterin1", "pw", "beraterin1");
    matrixUserClient.updateUserDisplayName(
        "@beraterin1:matrix.oriso.org", "Beratung wird jetzt von Frau M. fortgefuehrt.");

    assertThatCode(() -> assertNoRealNameReachedMatrix(matrixUserClient, FIRST, LAST))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("one name part alone is not a leak")
  void assertNoRealNameReachedMatrix_Should_Pass_When_OnlyOnePartIsPresent() {
    var matrixUserClient = mock(MatrixUserClient.class);
    matrixUserClient.updateUserDisplayName("@beraterin1:matrix.oriso.org", "Frau Musterfrau-Nord");

    assertThatCode(() -> assertNoRealNameReachedMatrix(matrixUserClient, FIRST, "Beispiel"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("distant mentions of both parts are not treated as the full name")
  void assertNoRealNameReachedMatrix_Should_Pass_When_ThePartsAreFarApart() {
    var matrixUserClient = mock(MatrixUserClient.class);
    matrixUserClient.updateUserDisplayName(
        "@beraterin1:matrix.oriso.org", "Angela is not the same token sequence as Musterfrau");

    assertThatCode(() -> assertNoRealNameReachedMatrix(matrixUserClient, FIRST, LAST))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("checks every Matrix collaborator handed to it")
  void assertNoRealNameReachedMatrix_Should_CoverSeveralMocks() {
    var clean = mock(MatrixUserClient.class);
    var leaking = mock(MatrixUserClient.class);
    clean.updateUserDisplayName("@a:oriso", "Frau M.");
    leaking.updateUserDisplayName("@b:oriso", "Angela Musterfrau");

    assertThatThrownBy(() -> assertNoRealNameReachedMatrix(List.of(clean, leaking), FIRST, LAST))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("refuses to pretend it guarded anything when a name part is missing")
  void assertNoRealNameReachedMatrix_Should_Reject_AnIncompleteName() {
    var matrixUserClient = mock(MatrixUserClient.class);

    assertThatThrownBy(() -> assertNoRealNameReachedMatrix(matrixUserClient, FIRST, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
