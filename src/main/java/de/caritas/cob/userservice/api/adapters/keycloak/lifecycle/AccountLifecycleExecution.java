package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The actual live saga transaction, not a reusable DTO, authorizes starting new owned effects. */
final class AccountLifecycleExecution {
  private static final Object RESOURCE = new Object();

  private record Held(
      AccountLifecycleJournal.Attempt attempt, DataSource source, Object connectionHolder) {}

  private AccountLifecycleExecution() {}

  static <T> T within(
      AccountLifecycleJournal.Attempt attempt, DataSource source, Supplier<T> work) {
    Object connection = TransactionSynchronizationManager.getResource(source);
    if (connection == null || TransactionSynchronizationManager.hasResource(RESOURCE))
      throw denied();
    TransactionSynchronizationManager.bindResource(RESOURCE, new Held(attempt, source, connection));
    try {
      return work.get();
    } finally {
      TransactionSynchronizationManager.unbindResource(RESOURCE);
    }
  }

  static void require(AccountLifecycleJournal.Attempt attempt, DataSource source) {
    Object resource = TransactionSynchronizationManager.getResource(RESOURCE);
    if (!(resource instanceof Held held)
        || !held.attempt().id().equals(attempt.id())
        || !held.attempt().execution().equals(attempt.execution())
        || held.connectionHolder() != TransactionSynchronizationManager.getResource(source))
      throw denied();
  }

  static boolean isCurrent(AccountLifecycleJournal.Attempt attempt) {
    var value = TransactionSynchronizationManager.getResource(RESOURCE);
    return TransactionSynchronizationManager.isActualTransactionActive()
        && value instanceof Held held
        && held.attempt().id().equals(attempt.id())
        && held.attempt().execution().equals(attempt.execution())
        && held.connectionHolder() == TransactionSynchronizationManager.getResource(held.source());
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("Owned effect is outside its live local saga transaction");
  }
}
