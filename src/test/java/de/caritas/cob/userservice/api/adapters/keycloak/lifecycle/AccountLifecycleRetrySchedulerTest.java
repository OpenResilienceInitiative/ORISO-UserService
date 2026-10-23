package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

class AccountLifecycleRetrySchedulerTest {
  private final JdbcAccountLifecycleJournal journal = mock(JdbcAccountLifecycleJournal.class);
  private final AccountLifecycleDispatcher dispatcher = mock(AccountLifecycleDispatcher.class);
  private final ClaimedPasswordMaintenance claimed = mock(ClaimedPasswordMaintenance.class);
  private final SelfPasswordMaintenance self = mock(SelfPasswordMaintenance.class);

  private AccountLifecycleRetryScheduler withRetention() {
    var constructor =
        assertDoesNotThrow(
            () ->
                AccountLifecycleRetryScheduler.class.getConstructor(
                    JdbcAccountLifecycleJournal.class, AccountLifecycleDispatcher.class,
                    ClaimedPasswordMaintenance.class, SelfPasswordMaintenance.class),
            "The existing retry scheduler must own both bounded secret-retention sweeps");
    return assertDoesNotThrow(() -> constructor.newInstance(journal, dispatcher, claimed, self));
  }

  @Test
  void existingTwoArgumentSchedulerKeepsTheExistingRetryBehavior() {
    new AccountLifecycleRetryScheduler(journal, dispatcher).retry();
    var ordered = inOrder(journal, dispatcher);
    ordered.verify(journal).claimExpired();
    ordered.verify(dispatcher).retryPending();
    verifyNoInteractions(claimed, self);
  }

  @Test
  void existingSchedulerAlsoSweepsBothOwnedPasswordStores() {
    withRetention().retry();
    var ordered = inOrder(journal, dispatcher, claimed, self);
    ordered.verify(journal).claimExpired();
    ordered.verify(dispatcher).retryPending();
    ordered.verify(claimed).expireSecretCustody();
    ordered.verify(self).expireSecretCustody();
    ordered.verifyNoMoreInteractions();
  }

  @Test
  void retryAndOneSecretStoreFailuresDoNotStarveTheOtherSecretStore() {
    doThrow(new IllegalStateException("fixture claim failure")).when(journal).claimExpired();
    doThrow(new IllegalStateException("fixture dispatch failure")).when(dispatcher).retryPending();
    when(claimed.expireSecretCustody())
        .thenThrow(new IllegalStateException("fixture retention failure"));
    assertDoesNotThrow(() -> withRetention().retry());
    verify(self).expireSecretCustody();
  }

  @Test
  void selfSecretStoreFailureDoesNotPreventTheNextRetryCycle() {
    when(self.expireSecretCustody())
        .thenThrow(new IllegalStateException("fixture retention failure"));
    var scheduler = withRetention();
    assertDoesNotThrow(scheduler::retry);
    assertDoesNotThrow(scheduler::retry);
    verify(journal, times(2)).claimExpired();
    verify(dispatcher, times(2)).retryPending();
    verify(claimed, times(2)).expireSecretCustody();
    verify(self, times(2)).expireSecretCustody();
  }
}
