package de.caritas.cob.userservice.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The "never persist a half choice" invariant (#1046). Both the admin create/update path and the
 * public onboarding wizard funnel through {@link ConsultantAvatars#apply}, so these cases cover the
 * persisted shape for every caller.
 */
class ConsultantAvatarsTest {

  @Test
  void apply_Should_storeIconWithItsMotifId() {
    var consultant = new Consultant();

    ConsultantAvatars.apply(consultant, ConsultantAvatarKind.ICON, "motif-24");

    assertThat(consultant.getAvatarKind()).isEqualTo(ConsultantAvatarKind.ICON);
    assertThat(consultant.getAvatarId()).isEqualTo("motif-24");
  }

  @Test
  void apply_Should_demoteIconWithoutMotifIdToInitials() {
    var consultant = new Consultant();

    ConsultantAvatars.apply(consultant, ConsultantAvatarKind.ICON, "   ");

    assertThat(consultant.getAvatarKind()).isEqualTo(ConsultantAvatarKind.INITIALS);
    assertThat(consultant.getAvatarId()).isNull();
  }

  @Test
  void apply_Should_dropMotifIdForNonIconKinds() {
    var consultant = new Consultant();

    ConsultantAvatars.apply(consultant, ConsultantAvatarKind.INITIALS, "motif-24");
    assertThat(consultant.getAvatarKind()).isEqualTo(ConsultantAvatarKind.INITIALS);
    assertThat(consultant.getAvatarId()).isNull();

    ConsultantAvatars.apply(consultant, ConsultantAvatarKind.PICTURE, "motif-24");
    assertThat(consultant.getAvatarKind()).isEqualTo(ConsultantAvatarKind.PICTURE);
    assertThat(consultant.getAvatarId()).isNull();
  }

  @Test
  void apply_Should_clearBothColumns_When_noKindIsResolved() {
    var consultant = new Consultant();
    consultant.setAvatarKind(ConsultantAvatarKind.ICON);
    consultant.setAvatarId("motif-24");

    ConsultantAvatars.apply(consultant, null, "motif-24");

    assertThat(consultant.getAvatarKind()).isNull();
    assertThat(consultant.getAvatarId()).isNull();
  }

  @Test
  void fromNameOrNull_Should_parseTheWireSpellings() {
    assertThat(ConsultantAvatarKind.fromNameOrNull("ICON")).isEqualTo(ConsultantAvatarKind.ICON);
    assertThat(ConsultantAvatarKind.fromNameOrNull(" INITIALS "))
        .isEqualTo(ConsultantAvatarKind.INITIALS);
    assertThat(ConsultantAvatarKind.fromNameOrNull("PICTURE"))
        .isEqualTo(ConsultantAvatarKind.PICTURE);
  }

  @Test
  void fromNameOrNull_Should_answerNull_For_unknownOrBlankValues() {
    assertThat(ConsultantAvatarKind.fromNameOrNull(null)).isNull();
    assertThat(ConsultantAvatarKind.fromNameOrNull("")).isNull();
    assertThat(ConsultantAvatarKind.fromNameOrNull("  ")).isNull();
    assertThat(ConsultantAvatarKind.fromNameOrNull("icon")).isNull();
    assertThat(ConsultantAvatarKind.fromNameOrNull("<script>")).isNull();
  }
}
