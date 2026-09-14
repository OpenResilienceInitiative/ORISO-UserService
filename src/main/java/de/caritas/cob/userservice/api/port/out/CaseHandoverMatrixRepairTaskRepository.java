package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CaseHandoverMatrixRepairTask;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseHandoverMatrixRepairTaskRepository
    extends JpaRepository<CaseHandoverMatrixRepairTask, Long> {

  @Query(
      "SELECT task FROM CaseHandoverMatrixRepairTask task"
          + " WHERE task.attemptCount < :maxAttempts"
          + " AND (task.lastAttemptAt IS NULL OR task.lastAttemptAt < :retryBefore)"
          + " ORDER BY task.createDate ASC")
  List<CaseHandoverMatrixRepairTask> findRetryable(
      @Param("maxAttempts") int maxAttempts,
      @Param("retryBefore") LocalDateTime retryBefore,
      Pageable pageable);
}
