package de.caritas.cob.userservice.api.helper;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.Consultant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-002 §2: silent members are pseudonymous towards the advice seeker, who is a member of the
 * same room and can read every display name from {@code /joined_members}. These tests state that
 * invariant so a later refactor cannot quietly put real names back on the wire.
 */
class ConsultantDisplayNameResolverTest {

  private final ConsultantDisplayNameResolver resolver = new ConsultantDisplayNameResolver();

  private Consultant consultantWith(String username, String displayName) {
    var consultant = new Consultant();
    consultant.setUsername(username);
    consultant.setDisplayName(displayName);
    consultant.setFirstName("Angela");
    consultant.setLastName("Musterfrau");
    return consultant;
  }

  @Test
  @DisplayName("never exposes the counsellor's real name")
  void resolveMatrixDisplayName_Should_NeverReturnTheRealName() {
    var withDisplayName =
        resolver.resolveMatrixDisplayName(consultantWith("beraterin1", "Frau M."));
    var withoutDisplayName = resolver.resolveMatrixDisplayName(consultantWith("beraterin1", null));

    assertThat(withDisplayName).doesNotContain("Angela").doesNotContain("Musterfrau");
    assertThat(withoutDisplayName).doesNotContain("Angela").doesNotContain("Musterfrau");
  }

  @Test
  @DisplayName("uses the app-level display name the client already sees")
  void resolveMatrixDisplayName_Should_PreferTheAppDisplayName() {
    assertThat(resolver.resolveMatrixDisplayName(consultantWith("beraterin1", "Frau M.")))
        .isEqualTo("Frau M.");
  }

  @Test
  @DisplayName("falls back to the username, which the Matrix ID already exposes")
  void resolveMatrixDisplayName_Should_FallBackToTheUsername_When_NoDisplayNameIsSet() {
    assertThat(resolver.resolveMatrixDisplayName(consultantWith("beraterin1", "  ")))
        .isEqualTo("beraterin1");
    assertThat(resolver.resolveMatrixDisplayName(consultantWith("beraterin1", null)))
        .isEqualTo("beraterin1");
  }

  @Test
  @DisplayName("treats an encoded display name as absent rather than rendering noise")
  void resolveMatrixDisplayName_Should_IgnoreAnEncodedDisplayName() {
    assertThat(resolver.resolveMatrixDisplayName(consultantWith("beraterin1", "enc.MFRGGZDF")))
        .isEqualTo("beraterin1");
  }

  @Test
  @DisplayName("tolerates a missing consultant")
  void resolveMatrixDisplayName_Should_ReturnNull_When_ConsultantIsNull() {
    assertThat(resolver.resolveMatrixDisplayName(null)).isNull();
  }

  // ---------------------------------------------------------------------------
  // resolveInternalDisplayName — internal surfaces (team lists, supervision marker, #996)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("internal surfaces prefer the internal display name")
  void resolveInternalDisplayName_Should_PreferTheInternalDisplayName() {
    assertThat(resolver.resolveInternalDisplayName("Team Angela", "Frau A.", "beraterin1"))
        .isEqualTo("Team Angela");
  }

  @Test
  @DisplayName("falls back to the public display name, then to the decoded username")
  void resolveInternalDisplayName_Should_FallBackToPublicNameThenUsername() {
    assertThat(resolver.resolveInternalDisplayName(" ", "Frau A.", "beraterin1"))
        .isEqualTo("Frau A.");
    assertThat(resolver.resolveInternalDisplayName(null, null, "beraterin1"))
        .isEqualTo("beraterin1");
  }

  @Test
  @DisplayName("an encoded internal name is treated as absent")
  void resolveInternalDisplayName_Should_IgnoreAnEncodedInternalName() {
    assertThat(resolver.resolveInternalDisplayName("enc.QW5nZWxh", null, "beraterin1"))
        .isEqualTo("beraterin1");
  }

  @Test
  @DisplayName("the entity overload applies the same rule")
  void resolveInternalDisplayName_Should_AcceptTheConsultantEntity() {
    var consultant = consultantWith("beraterin1", "Frau A.");
    consultant.setInternalDisplayName("Team Angela");

    assertThat(resolver.resolveInternalDisplayName(consultant)).isEqualTo("Team Angela");
    assertThat(resolver.resolveInternalDisplayName((Consultant) null)).isNull();
  }
}
