package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TutorialProgress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface TutorialProgressRepository extends CrudRepository<TutorialProgress, Long> {

  @Query(
      value = "SELECT GET_LOCK(SHA2(CONCAT('tutorial-progress:', :userId), 256), :timeoutSeconds)",
      nativeQuery = true)
  Integer acquireUserProgressLock(
      @Param("userId") String userId, @Param("timeoutSeconds") int timeoutSeconds);

  @Query(
      value = "SELECT RELEASE_LOCK(SHA2(CONCAT('tutorial-progress:', :userId), 256))",
      nativeQuery = true)
  Integer releaseUserProgressLock(@Param("userId") String userId);

  Optional<TutorialProgress> findByUserIdAndSurfaceAndTourIdAndTourVersion(
      String userId, String surface, String tourId, Integer tourVersion);

  List<TutorialProgress> findByUserIdAndSurface(String userId, String surface);

  /** Number of progress rows a user owns — used to cap per-user row growth. */
  long countByUserId(String userId);

  /**
   * Atomically creates or updates one versioned progress scope.
   *
   * <p>The unique scope constraint is shared by every replica. Using MariaDB's native upsert keeps
   * concurrent first writes on the normal SQL path instead of emitting duplicate-key warnings and
   * recovering from an exception.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tutorial_progress (
            user_id, surface, tour_id, tour_version, status, current_step_id,
            started_at, completed_at, create_date, update_date, tenant_id
          ) VALUES (
            :userId, :surface, :tourId, :tourVersion, :status, :currentStepId,
            :startedAt, :completedAt, :createDate, :updateDate, :tenantId
          )
          ON DUPLICATE KEY UPDATE
            status = VALUES(status),
            current_step_id = VALUES(current_step_id),
            completed_at = VALUES(completed_at),
            update_date = VALUES(update_date)
          """,
      nativeQuery = true)
  int upsertScopedProgress(
      @Param("userId") String userId,
      @Param("surface") String surface,
      @Param("tourId") String tourId,
      @Param("tourVersion") Integer tourVersion,
      @Param("status") String status,
      @Param("currentStepId") String currentStepId,
      @Param("startedAt") LocalDateTime startedAt,
      @Param("completedAt") LocalDateTime completedAt,
      @Param("createDate") LocalDateTime createDate,
      @Param("updateDate") LocalDateTime updateDate,
      @Param("tenantId") Long tenantId);
}
