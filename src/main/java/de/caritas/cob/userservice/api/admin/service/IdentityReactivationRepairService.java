package de.caritas.cob.userservice.api.admin.service;

import de.caritas.cob.userservice.api.exception.identity.IdentityReactivationCompensationException;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Durably records and retries a failed Keycloak disable after database rollback. */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdentityReactivationRepairService {

  static final String REPAIR_METRIC = "userservice.identity.reactivation.repair";

  private final @NonNull IdentityDeactivator identityDeactivator;
  private final @NonNull IdentityReactivationRepairWriter repairWriter;
  private final @NonNull UserRepository userRepository;
  private final @NonNull MeterRegistry meterRegistry;

  /**
   * Attempts to disable the identity. The thrown typed exception surfaces the partial rollback to
   * the HTTP error contract when the database failure is observable before transaction completion;
   * the transaction callback then persists the repair marker after releasing its user-row lock.
   */
  public void compensate(String userId, RuntimeException databaseFailure) {
    metric("attempt");
    try {
      identityDeactivator.deactivateUser(userId);
      metric("disabled");
    } catch (RuntimeException compensationFailure) {
      throw new IdentityReactivationCompensationException(
          "Keycloak rollback failed", databaseFailure, compensationFailure);
    }
  }

  /**
   * Persists repair only after the failed caller transaction released its row lock. Calling this
   * from inside that transaction would deadlock the independent writer on the same user row.
   */
  public void queueFailedCompensation(
      String userId, IdentityReactivationCompensationException compensationFailure) {
    boolean queued;
    try {
      queued = repairWriter.markRepairRequired(userId);
    } catch (RuntimeException persistenceFailure) {
      metric("queue_failed");
      throw durableStateFailure(userId, compensationFailure, persistenceFailure);
    }
    metric(queued ? "queued" : "queue_failed");
    if (queued) {
      log.error(
          "Queued durable Keycloak disable repair after asker reactivation rollback for userId={}",
          userId,
          compensationFailure);
      return;
    }
    throw durableStateFailure(
        userId,
        compensationFailure,
        new IllegalStateException("Could not persist durable identity reactivation repair state"));
  }

  private IdentityReactivationCompensationException durableStateFailure(
      String userId,
      IdentityReactivationCompensationException compensationFailure,
      RuntimeException persistenceFailure) {
    persistenceFailure.addSuppressed(compensationFailure);
    log.error(
        "CRITICAL: Keycloak rollback and durable repair persistence both failed for userId={}",
        userId,
        persistenceFailure);
    return new IdentityReactivationCompensationException(
        "Keycloak rollback and durable repair persistence both failed",
        compensationFailure,
        persistenceFailure);
  }

  /** Retries every durable repair while leaving failed rows blocked for later scheduler runs. */
  public void retryOutstandingRepairs() {
    userRepository
        .findAllByDeletionLifecycleStateOrderByCreateDateAsc(
            DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED)
        .forEach(this::retryRepair);
  }

  private void retryRepair(User user) {
    String userId = user.getUserId();
    metric("retry_attempt");
    try {
      identityDeactivator.deactivateUser(userId);
      if (repairWriter.resolveRepair(userId)) {
        metric("retry_resolved");
        log.info("Resolved durable Keycloak disable repair for userId={}", userId);
      } else {
        metric("retry_state_changed");
        log.warn(
            "Durable Keycloak disable repair state changed before resolve for userId={}", userId);
      }
    } catch (RuntimeException exception) {
      metric("retry_failed");
      log.error("Durable Keycloak disable repair retry failed for userId={}", userId, exception);
    }
  }

  private void metric(String outcome) {
    meterRegistry.counter(REPAIR_METRIC, "outcome", outcome).increment();
  }
}
