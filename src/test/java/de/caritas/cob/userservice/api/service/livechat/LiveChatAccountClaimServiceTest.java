package de.caritas.cob.userservice.api.service.livechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LiveChatAccountClaimServiceTest {
  private final UserRepository users = mock(UserRepository.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final LiveChatAccountClaimRepository records = mock(LiveChatAccountClaimRepository.class);
  private final LiveChatAccountClaimService claims =
      new LiveChatAccountClaimService(users, sessions, records);
  private static final String INTENT = "123e4567-e89b-12d3-a456-426614174000";

  @Test
  void pendingClaimBlocksCleanupAndRejectsDifferentPasswordOrIntent() {
    var user = guest();
    when(records.findForUpdate("guest")).thenReturn(Optional.empty());
    var record = claims.claimConversion("guest", INTENT, "chosen-password-123");
    assertThat(record.getLifecycle()).isEqualTo(LiveChatAccountLifecycle.PROMOTION_PENDING);
    when(records.findForUpdate("guest")).thenReturn(Optional.of(record));
    assertThat(claims.claimCleanup("guest")).isFalse();
    claims.claimConversion("guest", INTENT, "chosen-password-123");
    assertThatThrownBy(() -> claims.claimConversion("guest", INTENT, "different-password-123"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                claims.claimConversion(
                    "guest", "123e4567-e89b-12d3-a456-426614174001", "chosen-password-123"))
        .isInstanceOf(RuntimeException.class);
    verify(users, never()).save(user);
  }

  @Test
  void cleanupClaimPreventsConversion() {
    guest();
    when(records.findForUpdate("guest")).thenReturn(Optional.empty());
    when(records.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    assertThat(claims.claimCleanup("guest")).isTrue();
    var saved = org.mockito.ArgumentCaptor.forClass(LiveChatAccountClaim.class);
    verify(records).save(saved.capture());
    when(records.findForUpdate("guest")).thenReturn(Optional.of(saved.getValue()));
    assertThatThrownBy(() -> claims.claimConversion("guest", INTENT, "chosen-password-123"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void rejectsTemporaryDirectLinkAccountsEvenIfTheyAlsoHaveAnAnonymousSession() {
    var user = guest();
    user.setTemporaryAccount(true);
    assertThatThrownBy(() -> claims.claimConversion("guest", INTENT, "chosen-password-123"))
        .isInstanceOf(RuntimeException.class);
    verifyNoInteractions(records);
  }

  @Test
  void statusExposesOwnIntentButNeverTheCredentialVerifier() {
    guest();
    var claim = new LiveChatAccountClaim("guest", LiveChatAccountLifecycle.PROMOTION_PENDING);
    claim.setIntentId(INTENT);
    claim.setCredentialVerifier("secret-verifier");
    when(records.findById("guest")).thenReturn(Optional.of(claim));
    var status = claims.status("guest");
    assertThat(status.state()).isEqualTo("PROMOTION_PENDING");
    assertThat(status.intentId()).isEqualTo(INTENT);
    assertThat(status.toString()).doesNotContain("secret-verifier");
  }

  @Test
  void verifierDistinguishesPasswordsBeyondBcryptByteLimit() {
    var prefix = "x".repeat(80);
    var verifier = ConversionCredentialVerifier.encode(prefix + "first");
    assertThat(ConversionCredentialVerifier.matches(prefix + "first", verifier)).isTrue();
    assertThat(ConversionCredentialVerifier.matches(prefix + "second", verifier)).isFalse();
  }

  private User guest() {
    var user = new User();
    user.setUserId("guest");
    when(users.findByUserIdForUpdate("guest")).thenReturn(Optional.of(user));
    var session = new Session();
    session.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    when(sessions.findByUser(user)).thenReturn(List.of(session));
    when(records.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    return user;
  }
}
