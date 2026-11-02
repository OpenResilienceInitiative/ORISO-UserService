package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.StatisticsDashboardSnapshot;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StatisticsDashboardSnapshotRepository
    extends JpaRepository<StatisticsDashboardSnapshot, Long> {

  Optional<StatisticsDashboardSnapshot> findByScopeAndTenantIdAndSnapshotIntervalAndCutoffAt(
      String scope, Long tenantId, String snapshotInterval, LocalDateTime cutoffAt);

  Optional<StatisticsDashboardSnapshot> findFirstByScopeAndTenantIdOrderByComputedAtDesc(
      String scope, Long tenantId);

  @Modifying
  @Query(
      "DELETE FROM StatisticsDashboardSnapshot s "
          + "WHERE s.scope = :scope AND s.tenantId = :tenantId AND s.id <> :keepId")
  int deleteOtherStands(
      @Param("scope") String scope, @Param("tenantId") Long tenantId, @Param("keepId") Long keepId);

  @Modifying
  @Query("DELETE FROM StatisticsDashboardSnapshot s WHERE s.scope = :scope")
  int deleteByScope(@Param("scope") String scope);

  @Modifying
  @Query("DELETE FROM StatisticsDashboardSnapshot s WHERE s.computedAt < :before")
  int deleteComputedBefore(@Param("before") LocalDateTime before);
}
