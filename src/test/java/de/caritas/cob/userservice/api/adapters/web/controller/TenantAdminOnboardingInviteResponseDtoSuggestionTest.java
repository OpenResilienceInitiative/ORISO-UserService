package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.web.controller.TenantAdminOnboardingController.TenantAdminOnboardingInviteResponseDTO;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.ConsultantAvatarKind;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.CounsellorOnboardingState;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenantAdminOnboardingInviteResponseDtoSuggestionTest {

  private static CounsellorOnboardingState stateOf(AccountInvite invite) {
    return new CounsellorOnboardingState(invite, false, List.of(), List.of(), true, false);
  }

  @Test
  void from_Should_ReturnTheInvitersSuggestion() {
    AccountInvite invite =
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .recipientEmail("new@example.org")
            .suggestedAvatarKind(ConsultantAvatarKind.ICON)
            .suggestedAvatarId("owl")
            .suggestedDisplayName("Maria B.")
            .build();

    TenantAdminOnboardingInviteResponseDTO dto =
        TenantAdminOnboardingInviteResponseDTO.from(stateOf(invite));

    assertThat(dto.avatarKind).isEqualTo("ICON");
    assertThat(dto.avatarId).isEqualTo("owl");
    assertThat(dto.displayName).isEqualTo("Maria B.");
  }

  @Test
  void from_Should_StillResolveAnInviteCreatedBeforeTheSuggestionExisted() {
    AccountInvite oldInvite =
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .recipientEmail("old@example.org")
            .build();

    TenantAdminOnboardingInviteResponseDTO dto =
        TenantAdminOnboardingInviteResponseDTO.from(stateOf(oldInvite));

    assertThat(dto.recipientEmail).isEqualTo("old@example.org");
    assertThat(dto.avatarKind).isNull();
    assertThat(dto.avatarId).isNull();
    assertThat(dto.displayName).isNull();
  }
}
