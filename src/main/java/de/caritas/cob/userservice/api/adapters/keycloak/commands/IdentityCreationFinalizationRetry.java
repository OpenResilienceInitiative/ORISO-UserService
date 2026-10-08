package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expired attempts first acquire the native owner-bound recovery claim before any local cleanup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdentityCreationFinalizationRetry {
  private final IdentityCreationJournalWriter journal;
  private final IdentityAccountProvisioning provisioning;
  private final IdentityCreationLocalCleanup cleanup;
  private final IdentityAnonymousBootstrapFailure bootstrap;

  @Scheduled(fixedDelayString = "${oriso.commands.finalization-retry-delay-ms:60000}")
  public void retry() {
    for (var row : journal.reconciliationRequired()) {
      try {
        if (CreationStatus.RECOVERY_REQUESTED.matches(row)) provisioning.recover(row);
        // Recovery may have resolved an absent native creation with its tombstone.
        if (!CreationStatus.COMPENSATED.matches(journal.attempt(UUID.fromString(row.getId()))))
          cleanup.clean(UUID.fromString(row.getId()));
      } catch (RuntimeException failure) {
        log.warn(
            "Owned identity creation recovery remains pending for attempt {} ({})",
            row.getId(),
            failure.getClass().getSimpleName());
      }
    }
    for (var row : journal.pending()) {
      try {
        if (!CreationStatus.in(
            row, CreationStatus.COMMIT_REQUESTED, CreationStatus.COMPENSATION_REQUESTED)) continue;
        var origin = IdentityCreationOrigin.pendingFinalization(row);
        var receipt =
            new KeycloakTaskCommands.CreationResult(
                UUID.fromString(row.getId()), row.getAccountId(), row.getCreationProof(), "OPEN");
        if (CreationStatus.COMMIT_REQUESTED.matches(row)) provisioning.commit(receipt, origin);
        else {
          cleanup.clean(receipt.attemptId());
          provisioning.compensate(receipt, origin);
        }
      } catch (RuntimeException failure) {
        log.warn(
            "Identity creation finalization remains pending ({})",
            failure.getClass().getSimpleName());
      }
    }
    for (var row : journal.pendingAnonymousBootstraps()) {
      try {
        bootstrap.reconcile(UUID.fromString(row.getId()));
      } catch (RuntimeException failure) {
        log.warn(
            "Owned guest bootstrap cleanup remains pending for attempt {} ({})",
            row.getId(),
            failure.getClass().getSimpleName());
      }
    }
  }
}
