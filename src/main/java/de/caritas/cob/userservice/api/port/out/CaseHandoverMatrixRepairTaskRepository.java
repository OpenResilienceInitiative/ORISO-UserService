package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CaseHandoverMatrixRepairTask;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT task FROM CaseHandoverMatrixRepairTask task"
          + " WHERE task.action = :action AND task.roomId = :roomId AND task.memberId = :memberId")
  Optional<CaseHandoverMatrixRepairTask> findExistingForUpdate(
      @Param("action") de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairAction action,
      @Param("roomId") String roomId,
      @Param("memberId") String memberId);

  @Query(
      "SELECT task FROM CaseHandoverMatrixRepairTask task"
          + " WHERE task.action = :action AND task.memberId = :memberId")
  List<CaseHandoverMatrixRepairTask> findByActionAndMemberId(
      @Param("action") de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairAction action,
      @Param("memberId") String memberId);

  /**
   * Drops the pending tasks of one member for the cases of one Träger, e.g. when they return. Does
   * not clear the persistence context: it runs inside a larger change that holds loaded entities.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      "DELETE FROM CaseHandoverMatrixRepairTask task"
          + " WHERE task.action = :action AND task.memberId = :memberId"
          + " AND task.sessionId IN (SELECT s.id FROM Session s WHERE s.tenantId = :tenantId"
          + " OR (s.tenantId IS NULL AND :tenantId = 1))")
  int deleteByActionAndMemberIdInTenant(
      @Param("action") de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairAction action,
      @Param("memberId") String memberId,
      @Param("tenantId") Long tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT task FROM CaseHandoverMatrixRepairTask task WHERE task.id = :id")
  Optional<CaseHandoverMatrixRepairTask> findByIdForUpdate(@Param("id") Long id);
}
