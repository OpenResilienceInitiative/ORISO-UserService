package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.adapters.web.dto.ReassignmentNotificationDTO;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Sends existing takeover mail only after its domain transaction commits. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CaseHandoverEmailNotification {
  private final EmailNotificationFacade emailNotificationFacade;

  public void takeoverConsentRequested(Long requestId, String matrixRoomId, TenantData tenant) {
    TenantData snapshot = snapshot(tenant);
    schedule(
        requestId,
        "consent",
        () -> emailNotificationFacade.sendReassignRequestNotification(matrixRoomId, snapshot));
  }

  public void ownershipGranted(
      Long requestId,
      String matrixRoomId,
      UUID newConsultantId,
      String previousConsultantName,
      TenantData tenant) {
    TenantData snapshot = snapshot(tenant);
    var notification =
        new ReassignmentNotificationDTO(matrixRoomId, newConsultantId)
            .fromConsultantName(previousConsultantName);
    schedule(
        requestId,
        "granted",
        () -> emailNotificationFacade.sendReassignConfirmationNotification(notification, snapshot));
  }

  private TenantData snapshot(TenantData tenant) {
    Objects.requireNonNull(tenant, "The committed handover tenant is required");
    Objects.requireNonNull(tenant.getTenantId(), "The handover tenant ID is required");
    return new TenantData(tenant.getTenantId(), tenant.getSubdomain());
  }

  private void schedule(Long requestId, String outcome, Runnable delivery) {
    Objects.requireNonNull(requestId, "The persisted handover request is required");
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Handover mail requires an active domain transaction");
    }
    var key = new Outcome(requestId, outcome);
    boolean alreadyScheduled =
        TransactionSynchronizationManager.getSynchronizations().stream()
            .anyMatch(item -> item instanceof Delivery existing && existing.key.equals(key));
    if (!alreadyScheduled) {
      TransactionSynchronizationManager.registerSynchronization(new Delivery(key, delivery));
    }
  }

  private record Outcome(Long requestId, String kind) {}

  private static final class Delivery implements TransactionSynchronization {
    private final Outcome key;
    private final Runnable delivery;

    private Delivery(Outcome key, Runnable delivery) {
      this.key = key;
      this.delivery = delivery;
    }

    @Override
    public void afterCommit() {
      try {
        delivery.run();
      } catch (RuntimeException failure) {
        // Ownership already committed. Do not turn dispatch failure into a misleading rollback.
        log.warn(
            "Handover {} mail dispatch failed after commit ({})",
            key.kind(),
            failure.getClass().getSimpleName());
      }
    }
  }
}
