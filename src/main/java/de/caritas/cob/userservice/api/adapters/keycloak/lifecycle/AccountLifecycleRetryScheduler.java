package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

/**
 * Durable intents and expiry claims survive process loss; no request token or password is retained.
 * The scheduled bean remains proxyable by the existing runtime scheduler metrics aspect.
 */
public class AccountLifecycleRetryScheduler {
  private final JdbcAccountLifecycleJournal journal;
  private final AccountLifecycleDispatcher dispatcher;
  private final ClaimedPasswordMaintenance claimed;
  private final SelfPasswordMaintenance self;

  public AccountLifecycleRetryScheduler(
      JdbcAccountLifecycleJournal journal, AccountLifecycleDispatcher dispatcher) {
    this.journal = journal;
    this.dispatcher = dispatcher;
    this.claimed = null;
    this.self = null;
  }

  public AccountLifecycleRetryScheduler(
      JdbcAccountLifecycleJournal journal,
      AccountLifecycleDispatcher dispatcher,
      ClaimedPasswordMaintenance claimed,
      SelfPasswordMaintenance self) {
    this.journal = java.util.Objects.requireNonNull(journal);
    this.dispatcher = java.util.Objects.requireNonNull(dispatcher);
    this.claimed = java.util.Objects.requireNonNull(claimed);
    this.self = java.util.Objects.requireNonNull(self);
  }

  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${identity.lifecycle.retry-delay-ms:30000}",
      initialDelayString = "${identity.lifecycle.retry-initial-delay-ms:30000}")
  public void retry() {
    try {
      journal.claimExpired();
    } catch (RuntimeException failure) {
      /* durable attempt remains retryable; no sensitive diagnostics */
    }
    if (claimed == null) {
      dispatcher.retryPending();
      return;
    }
    try {
      dispatcher.retryPending();
    } catch (RuntimeException failure) {
      /* Each owned retention queue still gets its bounded turn. */
    }
    try {
      claimed.expireSecretCustody();
    } catch (RuntimeException failure) {
      /* Keep custody untouched if its independent owner checks fail. */
    }
    try {
      self.expireSecretCustody();
    } catch (RuntimeException failure) {
      /* A later retry uses the same deadline and immutable intent metadata. */
    }
  }
}
