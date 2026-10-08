package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GroupChatMatrixCleanupTask;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupChatMatrixCleanupTaskRepository
    extends JpaRepository<GroupChatMatrixCleanupTask, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT task FROM GroupChatMatrixCleanupTask task WHERE task.id=:id")
  Optional<GroupChatMatrixCleanupTask> findByIdForUpdate(@Param("id") Long id);

  @Query(
      "SELECT task FROM GroupChatMatrixCleanupTask task WHERE task.lastAttemptAt IS NULL OR task.lastAttemptAt < :before ORDER BY task.createdAt,task.id")
  List<GroupChatMatrixCleanupTask> findReady(@Param("before") LocalDateTime before, Pageable page);
}
