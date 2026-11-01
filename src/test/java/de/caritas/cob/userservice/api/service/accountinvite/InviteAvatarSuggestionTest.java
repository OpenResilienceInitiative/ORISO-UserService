package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.ConsultantAvatarKind;
import org.junit.jupiter.api.Test;

class InviteAvatarSuggestionTest {

  @Test
  void of_Should_KeepIconWithMotifAndTrimmedName() {
    InviteAvatarSuggestion suggestion = InviteAvatarSuggestion.of("ICON", " owl ", " Maria B. ");

    assertThat(suggestion.kind()).isEqualTo(ConsultantAvatarKind.ICON);
    assertThat(suggestion.avatarId()).isEqualTo("owl");
    assertThat(suggestion.displayName()).isEqualTo("Maria B.");
  }

  @Test
  void of_Should_DemoteIconWithoutMotifToInitials() {
    InviteAvatarSuggestion suggestion = InviteAvatarSuggestion.of("ICON", " ", null);

    assertThat(suggestion.kind()).isEqualTo(ConsultantAvatarKind.INITIALS);
    assertThat(suggestion.avatarId()).isNull();
  }

  @Test
  void of_Should_DropMotifForNonIconKinds() {
    assertThat(InviteAvatarSuggestion.of("INITIALS", "owl", null).avatarId()).isNull();
    assertThat(InviteAvatarSuggestion.of("PICTURE", "owl", null).avatarId()).isNull();
    assertThat(InviteAvatarSuggestion.of("PICTURE", "owl", null).kind())
        .isEqualTo(ConsultantAvatarKind.PICTURE);
  }

  @Test
  void of_Should_ReturnNameOnly_When_NoKindGiven() {
    InviteAvatarSuggestion suggestion = InviteAvatarSuggestion.of(null, "owl", "Maria B.");

    assertThat(suggestion.kind()).isNull();
    assertThat(suggestion.avatarId()).isNull();
    assertThat(suggestion.displayName()).isEqualTo("Maria B.");
  }

  @Test
  void of_Should_ReturnNothing_When_AllBlank() {
    assertThat(InviteAvatarSuggestion.of("", " ", null)).isEqualTo(InviteAvatarSuggestion.NONE);
  }

  @Test
  void of_Should_RejectUnknownKind() {
    assertThatThrownBy(() -> InviteAvatarSuggestion.of("EMOJI", null, null))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void of_Should_RejectOverlongNameAndMotif() {
    assertThatThrownBy(() -> InviteAvatarSuggestion.of(null, null, "x".repeat(101)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> InviteAvatarSuggestion.of("ICON", "x".repeat(65), null))
        .isInstanceOf(BadRequestException.class);
    assertThat(InviteAvatarSuggestion.of(null, null, "x".repeat(100)).displayName()).hasSize(100);
  }

  @Test
  void applyTo_Should_WriteAllThreeColumns() {
    AccountInvite invite = new AccountInvite();

    InviteAvatarSuggestion.of("ICON", "otter", "Max").applyTo(invite);

    assertThat(invite.getSuggestedAvatarKind()).isEqualTo(ConsultantAvatarKind.ICON);
    assertThat(invite.getSuggestedAvatarId()).isEqualTo("otter");
    assertThat(invite.getSuggestedDisplayName()).isEqualTo("Max");
  }
}
