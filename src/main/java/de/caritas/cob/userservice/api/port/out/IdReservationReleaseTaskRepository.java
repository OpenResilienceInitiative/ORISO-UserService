package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.IdReservationReleaseTask;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseType;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdReservationReleaseTaskRepository
    extends JpaRepository<IdReservationReleaseTask, Long> {

  boolean existsByAllocationTypeAndReservedId(
      IdReservationReleaseType allocationType, Long reservedId);

  @Query(
      "SELECT task FROM IdReservationReleaseTask task"
          + " WHERE task.attemptCount < :maxAttempts"
          + " AND (task.lastAttemptAt IS NULL OR task.lastAttemptAt < :retryBefore)"
          + " ORDER BY task.createDate ASC")
  List<IdReservationReleaseTask> findRetryable(
      @Param("maxAttempts") int maxAttempts,
      @Param("retryBefore") LocalDateTime retryBefore,
      Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select task from IdReservationReleaseTask task where task.id = :id")
  Optional<IdReservationReleaseTask> findByIdForUpdate(Long id);
}
