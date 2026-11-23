package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TenantReservationReleaseTask;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantReservationReleaseTaskRepository
    extends JpaRepository<TenantReservationReleaseTask, Long> {

  boolean existsByTenantIdAndReleaseGeneration(Long tenantId, String releaseGeneration);

  @Query(
      "SELECT task.id FROM TenantReservationReleaseTask task"
          + " WHERE (task.lastAttemptAt IS NULL OR task.lastAttemptAt < :retryBefore)"
          + " ORDER BY COALESCE(task.lastAttemptAt, task.createDate) ASC, task.id ASC")
  List<Long> findRetryable(@Param("retryBefore") LocalDateTime retryBefore, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT task FROM TenantReservationReleaseTask task WHERE task.id = :id")
  Optional<TenantReservationReleaseTask> findByIdForUpdate(@Param("id") Long id);
}
