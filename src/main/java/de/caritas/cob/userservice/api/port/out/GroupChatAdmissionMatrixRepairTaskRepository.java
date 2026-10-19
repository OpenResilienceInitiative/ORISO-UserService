package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GroupChatAdmissionMatrixRepairTask;
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

public interface GroupChatAdmissionMatrixRepairTaskRepository
    extends JpaRepository<GroupChatAdmissionMatrixRepairTask, Long> {

  Optional<GroupChatAdmissionMatrixRepairTask> findByRequestId(Long requestId);

  Optional<GroupChatAdmissionMatrixRepairTask> findByRequestIdAndRoomId(
      Long requestId, String roomId);

  @Modifying
  @Query("DELETE FROM GroupChatAdmissionMatrixRepairTask task WHERE task.requestId = :requestId")
  int deleteByRequestId(@Param("requestId") Long requestId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT task FROM GroupChatAdmissionMatrixRepairTask task WHERE task.id = :id")
  Optional<GroupChatAdmissionMatrixRepairTask> findByIdForUpdate(@Param("id") Long id);

  @Query(
      "SELECT task FROM GroupChatAdmissionMatrixRepairTask task"
          + " WHERE task.lastAttemptAt IS NULL OR task.lastAttemptAt < :retryBefore"
          + " ORDER BY CASE WHEN task.lastAttemptAt IS NULL THEN 0 ELSE 1 END ASC,"
          + " task.lastAttemptAt ASC, task.createdAt ASC, task.id ASC")
  List<GroupChatAdmissionMatrixRepairTask> findReady(
      @Param("retryBefore") LocalDateTime retryBefore, Pageable pageable);
}
