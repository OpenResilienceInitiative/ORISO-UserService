package de.caritas.cob.userservice.api.admin.service;

import de.caritas.cob.userservice.api.exception.identity.IdentityReactivationCompensationException;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Observes and retries durable, generation-fenced identity reactivation claims. */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdentityReactivationRepairService {

  static final String REPAIR_METRIC = "userservice.identity.reactivation.repair";

  private final @NonNull IdentityReactivationRepairWriter repairWriter;
  private final @NonNull UserRepository userRepository;
  private final @NonNull MeterRegistry meterRegistry;

  @Value("${identity.reactivation.repair.staleAfter:PT5M}")
  private Duration staleAfter;

  /** Compensates a failed request without permitting a stale generation to affect a newer one. */
  public void compensate(
      IdentityReactivationOperation operation, RuntimeException reactivationFailure) {
    metric("attempt");
    try {
      boolean repaired = repairWriter.compensate(operation, reactivationFailure);
      metric(repaired ? "disabled" : "stale_skipped");
    } catch (IdentityReactivationCompensationException exception) {
      metric("queued");
      log.error(
          "Retained durable Keycloak disable repair for userId={} operationId={}",
          operation.userId(),
          operation.operationId(),
          exception);
      throw exception;
    } catch (RuntimeException persistenceFailure) {
      metric("queue_failed");
      log.error(
          "CRITICAL: identity compensation persistence failed for userId={} operationId={}",
          operation.userId(),
          operation.operationId(),
          persistenceFailure);
      throw persistenceFailure;
    }
  }

  /**
   * Retries failed repairs and crash-window claims only after the configured stale grace period.
   */
  public void retryOutstandingRepairs() {
    LocalDateTime staleBefore = LocalDateTime.now(ZoneOffset.UTC).minus(staleAfter);
    Stream.concat(
            userRepository
                .findAllByDeletionLifecycleStateOrderByCreateDateAsc(
                    DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED)
                .stream(),
            userRepository
                .findAllByDeletionLifecycleStateOrderByCreateDateAsc(
                    DeletionLifecycleState.REACTIVATION_IN_PROGRESS)
                .stream()
                .filter(user -> isStale(user, staleBefore)))
        .forEach(this::retryRepair);
  }

  private boolean isStale(User user, LocalDateTime staleBefore) {
    return user.getReactivationOperationStartedAt() == null
        || !user.getReactivationOperationStartedAt().isAfter(staleBefore);
  }

  private void retryRepair(User user) {
    String userId = user.getUserId();
    String operationId = user.getReactivationOperationId();
    if (operationId == null) {
      metric("invalid_claim");
      log.error("Durable identity repair claim has no operationId for userId={}", userId);
      return;
    }
    metric("retry_attempt");
    try {
      boolean repaired = repairWriter.retry(userId, operationId);
      metric(repaired ? "retry_resolved" : "retry_stale_skipped");
      if (repaired) {
        log.info(
            "Resolved durable Keycloak disable repair for userId={} operationId={}",
            userId,
            operationId);
      }
    } catch (IdentityReactivationCompensationException exception) {
      metric("retry_failed");
      log.error(
          "Durable Keycloak disable repair retry failed for userId={} operationId={}",
          userId,
          operationId,
          exception);
    } catch (RuntimeException persistenceFailure) {
      metric("retry_persistence_failed");
      log.error(
          "Durable identity repair persistence failed for userId={} operationId={}",
          userId,
          operationId,
          persistenceFailure);
    }
  }

  private void metric(String outcome) {
    meterRegistry.counter(REPAIR_METRIC, "outcome", outcome).increment();
  }
}
