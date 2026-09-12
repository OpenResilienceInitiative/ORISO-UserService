package de.caritas.cob.userservice.api.service.accountinvite.allocation;

import de.caritas.cob.userservice.api.model.IdReservationReleaseTask;
import de.caritas.cob.userservice.api.port.out.IdReservationReleaseTaskRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Executes idempotent external reservation releases and retains failures for later retry. */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdReservationReleaseProcessor {

  private final @NonNull IdReservationReleaseTaskRepository taskRepository;
  private final @NonNull TenantIdAllocationClient tenantIdAllocationClient;
  private final @NonNull AgencyIdAllocationClient agencyIdAllocationClient;

  @Value("${account-invite.reservation-release.max-attempts:10}")
  private int maxAttempts;

  @Value("${account-invite.reservation-release.retry-backoff:PT5M}")
  private Duration retryBackoff;

  @Transactional(readOnly = true)
  public List<Long> pendingTaskIds() {
    return taskRepository
        .findRetryable(maxAttempts, LocalDateTime.now().minus(retryBackoff), PageRequest.of(0, 100))
        .stream()
        .map(IdReservationReleaseTask::getId)
        .toList();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean process(Long taskId) {
    var task = taskRepository.findByIdForUpdate(taskId);
    if (task.isEmpty()) {
      return true;
    }

    IdReservationReleaseTask pending = task.get();
    boolean released = attemptRelease(pending);
    if (released) {
      taskRepository.delete(pending);
    } else {
      pending.setAttemptCount(pending.getAttemptCount() + 1);
      pending.setLastAttemptAt(LocalDateTime.now());
      taskRepository.save(pending);
    }
    return released;
  }

  private boolean attemptRelease(IdReservationReleaseTask task) {
    TenantData currentTenant = TenantContext.getCurrentTenantData();
    TenantData previousTenant =
        currentTenant == null
            ? null
            : new TenantData(currentTenant.getTenantId(), currentTenant.getSubdomain());
    try {
      if (task.getTenantContextId() != null) {
        TenantContext.setCurrentTenant(task.getTenantContextId());
      }
      return switch (task.getAllocationType()) {
        case TENANT -> tenantIdAllocationClient.release(task.getReservedId());
        case AGENCY -> agencyIdAllocationClient.release(task.getReservedId());
      };
    } catch (RuntimeException exception) {
      log.warn(
          "Reservation release task {} could not reach {} allocation ledger",
          task.getId(),
          task.getAllocationType(),
          exception);
      return false;
    } finally {
      if (previousTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.setCurrentTenantData(previousTenant);
      }
    }
  }
}
