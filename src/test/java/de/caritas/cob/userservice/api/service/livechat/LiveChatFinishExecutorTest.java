package de.caritas.cob.userservice.api.service.livechat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LiveChatFinishExecutorTest {
  private final LiveChatInactivityFence inactivity = mock(LiveChatInactivityFence.class);
  private final UserRepository users = mock(UserRepository.class);
  private final LiveChatAccountClaimRepository records = mock(LiveChatAccountClaimRepository.class);
  private final IdentityDeactivator identities = mock(IdentityDeactivator.class);
  private final LiveChatFinishExecutor executor =
      new LiveChatFinishExecutor(inactivity, users, records, identities);

  @Test
  void failedDisableRetainsPendingClaimForRetry() {
    var user = new User();
    var claim = new LiveChatAccountClaim("guest", LiveChatAccountLifecycle.FINISH_PENDING);
    when(users.findByUserIdForUpdate("guest")).thenReturn(Optional.of(user));
    when(records.findForUpdate("guest")).thenReturn(Optional.of(claim));
    doThrow(new IllegalStateException("identity unavailable"))
        .doNothing()
        .when(identities)
        .deactivateUser("guest");
    assertThatThrownBy(() -> executor.disableAndMarkFinished("guest"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(claim.getLifecycle()).isEqualTo(LiveChatAccountLifecycle.FINISH_PENDING);
    executor.disableAndMarkFinished("guest");
    assertThat(claim.getLifecycle()).isEqualTo(LiveChatAccountLifecycle.ANONYMOUS_FINISHED);
    executor.disableAndMarkFinished("guest");
    verify(identities, times(2)).deactivateUser("guest");
  }
}
