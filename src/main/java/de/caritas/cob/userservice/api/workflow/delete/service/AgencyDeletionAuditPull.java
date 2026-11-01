package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.port.out.DeletionAuditLogRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyDeletionAuditOutboxClient;
import de.caritas.cob.userservice.api.service.agency.AgencyDeletionAuditOutboxClient.PendingEntry;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Copies agency purges that AgencyService queued in its outbox into the deletion log (OBP 2.1
 * #130), then acknowledges them. Idempotent: an entry already in the log (from a run whose
 * acknowledgement failed) is not written again, and a row is acknowledged only once its entry is
 * actually stored, because {@link DeletionAuditService} swallows its own write failures.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgencyDeletionAuditPull {

  static final int BATCH = 100;
  private static final DeletionAuditTargetType AGENCY = DeletionAuditTargetType.AGENCY;
  private static final DeletionAuditOutcome EXECUTED = DeletionAuditOutcome.EXECUTED;

  private final @NonNull AgencyDeletionAuditOutboxClient outbox;
  private final @NonNull DeletionAuditService deletionAuditService;
  private final @NonNull DeletionAuditLogRepository deletionAuditLogRepository;

  /** Processes one batch; returns the number of rows acknowledged. */
  public int pullOnce() {
    var recorded = new ArrayList<Long>();
    for (PendingEntry row : outbox.fetchPending(BATCH)) {
      if (row.id() == null || row.agencyId() == null || !EXECUTED.name().equals(row.outcome())) {
        log.warn("Skipping unexpected agency deletion outbox row {}", row.id());
        continue;
      }
      String agencyId = String.valueOf(row.agencyId());
      if (!logged(agencyId)) {
        deletionAuditService.recordAsSystem(
            AGENCY, agencyId, row.tenantId(), EXECUTED, purgedAt(row.createdAt()));
        if (!logged(agencyId)) {
          continue;
        }
      }
      recorded.add(row.id());
    }
    if (!recorded.isEmpty()) {
      outbox.acknowledge(recorded);
    }
    return recorded.size();
  }

  /** AgencyService sends its UTC purge time as an ISO local date-time; unreadable means unknown. */
  private static LocalDateTime purgedAt(String createdAt) {
    if (createdAt == null) {
      return null;
    }
    try {
      return LocalDateTime.parse(createdAt);
    } catch (DateTimeParseException unreadable) {
      return null;
    }
  }

  private boolean logged(String agencyId) {
    return deletionAuditLogRepository.existsByTargetTypeAndTargetIdAndOutcome(
        AGENCY, agencyId, EXECUTED);
  }
}
