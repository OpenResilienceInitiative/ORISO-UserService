package de.caritas.cob.userservice.api.picture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.port.out.ConsultantPictureRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

/** Issue #1049: an invite that dies during intake must not persist the picture write. */
class ConsultantPictureStoreOnboardingTest {
  final ConsultantRepository consultants = mock(ConsultantRepository.class);
  final ConsultantPictureRepository pictures = mock(ConsultantPictureRepository.class);
  final ConsultantPictureAccess access = mock(ConsultantPictureAccess.class);
  final EntityManager entityManager = mock(EntityManager.class);
  final CounsellorOnboardingService onboarding = mock(CounsellorOnboardingService.class);
  final ConsultantPictureStore store =
      new ConsultantPictureStore(consultants, pictures, access, entityManager, onboarding);
  final String token = "raw-invite-token";

  @Test
  void replaceForOnboardingDoesNotPersistWhenTheInviteIsNoLongerResumable() {
    when(onboarding.requireOnboardingPictureInvite(token))
        .thenThrow(new AccountInviteLinkException(AccountInviteLinkException.Reason.CONSUMED));

    assertThatThrownBy(() -> store.replaceForOnboarding(token, new byte[] {1, 2, 3}, "image/png"))
        .isInstanceOf(AccountInviteLinkException.class);

    verify(onboarding).requireOnboardingPictureInvite(token);
    verifyNoInteractions(pictures, consultants);
  }

  @Test
  void writeInternalOnlyForOnboardingDoesNotPersistWhenTheInviteIsNoLongerResumable() {
    when(onboarding.requireOnboardingPictureInvite(token))
        .thenThrow(new AccountInviteLinkException(AccountInviteLinkException.Reason.CONSUMED));

    assertThatThrownBy(() -> store.writeInternalOnlyForOnboarding(token, false))
        .isInstanceOf(AccountInviteLinkException.class);

    verify(onboarding).requireOnboardingPictureInvite(token);
    verifyNoInteractions(pictures, consultants);
  }
}
