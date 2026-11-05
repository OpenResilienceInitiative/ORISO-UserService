package de.caritas.cob.userservice.api.service.livechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LiveChatAccountConversionExecutorTest {
  private static final String PASSWORD = "chosen-password-123";
  private static final String INTENT = "123e4567-e89b-12d3-a456-426614174000";
  private final UserRepository users = mock(UserRepository.class);
  private final LiveChatAccountClaimRepository records = mock(LiveChatAccountClaimRepository.class);
  private final IdentityClient identities = mock(IdentityClient.class);
  private final IdentityAuthentication authentication = mock(IdentityAuthentication.class);
  private final LiveChatAccountConversionExecutor executor =
      new LiveChatAccountConversionExecutor(users, records, identities, authentication);

  @Test
  void failedIdentityUpdateLeavesPendingClaimAndVerifierForSameRetry() {
    var claim = pending();
    when(identities.changePassword("guest", PASSWORD)).thenReturn(false, true);
    assertThatThrownBy(() -> executor.execute("guest", "guest-name", INTENT, PASSWORD))
        .isInstanceOf(RuntimeException.class);
    assertThat(claim.getLifecycle()).isEqualTo(LiveChatAccountLifecycle.PROMOTION_PENDING);
    executor.execute("guest", "guest-name", INTENT, PASSWORD);
    assertThat(claim.getLifecycle()).isEqualTo(LiveChatAccountLifecycle.PROMOTED);
    assertThat(claim.getCredentialVerifier()).isNull();
  }

  @Test
  void retryAfterIdentityChangedBeforeDatabaseCommitFinishesWithoutChangingPasswordAgain() {
    var claim = pending();
    when(authentication.verifyPasswordIgnoringSecondFactor(
            new UsernameTranscoder().encodeUsername("guest-name"), PASSWORD))
        .thenReturn(true);
    executor.execute("guest", "guest-name", INTENT, PASSWORD);
    assertThat(claim.getLifecycle()).isEqualTo(LiveChatAccountLifecycle.PROMOTED);
    assertThat(claim.getCredentialVerifier()).isNull();
    verifyNoInteractions(identities);
  }

  @Test
  void lostResponseRetryVerifiesPasswordAndDoesNotChangeItAgain() {
    var claim = pending();
    claim.setLifecycle(LiveChatAccountLifecycle.PROMOTED);
    claim.setCredentialVerifier(null);
    when(authentication.verifyPasswordIgnoringSecondFactor(
            new UsernameTranscoder().encodeUsername("guest-name"), PASSWORD))
        .thenReturn(true);
    executor.execute("guest", "guest-name", INTENT, PASSWORD);
    assertThatThrownBy(
            () -> executor.execute("guest", "guest-name", INTENT, "different-password-123"))
        .isInstanceOf(RuntimeException.class);
    verifyNoInteractions(identities);
  }

  @Test
  void conflictingPasswordCannotReachIdentityService() {
    pending();
    assertThatThrownBy(
            () -> executor.execute("guest", "guest-name", INTENT, "different-password-123"))
        .isInstanceOf(RuntimeException.class);
    verifyNoInteractions(identities);
  }

  private LiveChatAccountClaim pending() {
    var claim = new LiveChatAccountClaim("guest", LiveChatAccountLifecycle.PROMOTION_PENDING);
    claim.setIntentId(INTENT);
    claim.setCredentialVerifier(ConversionCredentialVerifier.encode(PASSWORD));
    when(users.findByUserIdForUpdate("guest")).thenReturn(Optional.of(new User()));
    when(records.findForUpdate("guest")).thenReturn(Optional.of(claim));
    return claim;
  }
}
