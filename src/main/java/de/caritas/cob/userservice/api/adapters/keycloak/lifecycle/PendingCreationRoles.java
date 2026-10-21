package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Only the currently locked new-account saga can use its intended roles before first activation.
 */
public final class PendingCreationRoles {
  private static final Object KEY = new Object();

  private record Bound(AccountLifecycleJournal.Attempt attempt, Set<String> roles) {}

  private PendingCreationRoles() {}

  static <T> T within(
      AccountLifecycleJournal.Attempt attempt, Set<String> roles, Supplier<T> work) {
    if (!AccountLifecycleExecution.isCurrent(attempt)
        || TransactionSynchronizationManager.hasResource(KEY)) throw denied();
    TransactionSynchronizationManager.bindResource(KEY, new Bound(attempt, Set.copyOf(roles)));
    try {
      return work.get();
    } finally {
      TransactionSynchronizationManager.unbindResource(KEY);
    }
  }

  public static boolean active() {
    return TransactionSynchronizationManager.hasResource(KEY);
  }

  public static boolean authorize(String account, Long tenant, Collection<String> requested) {
    Object value = TransactionSynchronizationManager.getResource(KEY);
    if (value == null) return false;
    if (!(value instanceof Bound bound)
        || !AccountLifecycleExecution.isCurrent(bound.attempt)
        || !Objects.equals(account, bound.attempt.accountId())
        || !Objects.equals(
            tenant == null ? null : tenant.toString(), bound.attempt.identity().scope().tenantId())
        || requested == null
        || !bound.roles.containsAll(requested)) throw denied();
    return true;
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("Role grant exceeds the live pending creation");
  }
}
