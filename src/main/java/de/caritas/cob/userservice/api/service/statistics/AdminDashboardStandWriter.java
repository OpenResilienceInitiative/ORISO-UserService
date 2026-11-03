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

  /**
   * Stores the new stand and removes older ones of the same interval in one step. A newer stand
   * stays, so an instance writing late cannot change the numbers of a cutoff. A stand of the other
   * interval stays too, so switching back and forth cannot refresh it; retention removes it.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void replace(StatisticsDashboardSnapshot stand) {
    repository.saveAndFlush(stand);
    repository.deleteOlderStands(
        stand.getScope(), stand.getTenantId(), stand.getSnapshotInterval(), stand.getCutoffAt());
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
