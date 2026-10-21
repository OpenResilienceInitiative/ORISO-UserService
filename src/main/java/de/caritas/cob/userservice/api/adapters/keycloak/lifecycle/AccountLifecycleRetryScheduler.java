package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

/**
 * Durable intents and expiry claims survive process loss; no request token or password is retained.
 */
public final class AccountLifecycleRetryScheduler {
  private final JdbcAccountLifecycleJournal journal;
  private final AccountLifecycleDispatcher dispatcher;

  public AccountLifecycleRetryScheduler(
      JdbcAccountLifecycleJournal journal, AccountLifecycleDispatcher dispatcher) {
    this.journal = journal;
    this.dispatcher = dispatcher;
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
    dispatcher.retryPending();
  }
}
