package de.caritas.cob.userservice.api.service.statistics;

import de.caritas.cob.userservice.api.model.StatisticsDashboardSnapshot;
import de.caritas.cob.userservice.api.port.out.StatisticsDashboardSnapshotRepository;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the isolated transactions that change stored stands, so a replica losing the unique key
 * fails only its own write.
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardStandWriter {

  private final @NonNull StatisticsDashboardSnapshotRepository repository;

  /** Stores the new stand and removes the older ones of this scope and tenant in one step. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void replace(StatisticsDashboardSnapshot stand) {
    var saved = repository.saveAndFlush(stand);
    repository.deleteOtherStands(saved.getScope(), saved.getTenantId(), saved.getId());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deleteScope(String scope) {
    repository.deleteByScope(scope);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deleteComputedBefore(LocalDateTime before) {
    repository.deleteComputedBefore(before);
  }
}
