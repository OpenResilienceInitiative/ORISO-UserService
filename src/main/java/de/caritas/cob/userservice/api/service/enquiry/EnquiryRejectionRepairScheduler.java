package de.caritas.cob.userservice.api.service.enquiry;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.EnquiryRejection;
import de.caritas.cob.userservice.api.port.out.EnquiryRejectionRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded recovery of original closure intents; each durable claim arbitrates replicas. */
@lombok.extern.slf4j.Slf4j
@Component
@Profile("!testing")
@RequiredArgsConstructor
public class EnquiryRejectionRepairScheduler {
  private final EnquiryRejectionRepository rejections;
  private final EnquiryRejectionService repair;
  private final EnquiryRejectionTransactions transactions;
  private final Clock clock;

  private record Pending(Long id, Long tenant, String generation) {}

  @Scheduled(fixedDelayString = "${enquiry.rejection.repair.delay-ms:60000}")
  public void retryPending() {
    var pending =
        TenantContext.supplyAcrossTenants(
            () ->
                rejections
                    .findDue(
                        EnquiryRejection.State.PENDING,
                        LocalDateTime.now(clock),
                        PageRequest.of(0, 20))
                    .stream()
                    .map(
                        row ->
                            new Pending(row.getSessionId(), row.getTenantId(), row.getGeneration()))
                    .toList());
    for (var task : pending) {
      if (task.tenant() == null || task.tenant() <= 0) continue;
      TenantContext.runIn(
          task.tenant(),
          () -> {
            try {
              repair.repair(task.id());
            } catch (ConflictException changedBinding) {
              // Only a proven snapshot conflict is deferred as BINDING.
              try {
                transactions.deferUnclaimedRepair(task.id(), task.generation());
              } catch (RuntimeException unavailable) {
                log.warn(
                    "Enquiry rejection conflict deferral failed for session {} ({})",
                    task.id(),
                    unavailable.getClass().getSimpleName());
              }
            } catch (RuntimeException failure) {
              // Preserve the durable retry/lease; do not label storage failures as changed rooms.
              log.warn(
                  "Enquiry rejection repair failed for session {} ({})",
                  task.id(),
                  failure.getClass().getSimpleName());
            }
          });
    }
  }
}
