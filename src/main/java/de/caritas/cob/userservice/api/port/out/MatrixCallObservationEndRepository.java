package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.MatrixCallObservationEnd;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatrixCallObservationEndRepository
    extends JpaRepository<MatrixCallObservationEnd, Long> {

  /**
   * Locking read: a registration waits for an end of the same person that is being written, and
   * then sees it (#1628: the end wins).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<MatrixCallObservationEnd> findForUpdateBySourceRoomIdAndCallIdAndActorMatrixId(
      String sourceRoomId, String callId, String actorMatrixId);

  /** The first end of a call that never got a registration; the call reads as ended. */
  Optional<MatrixCallObservationEnd> findFirstBySourceRoomIdAndCallIdOrderByEndedAtAsc(
      String sourceRoomId, String callId);

  /**
   * Retention purge: ends older than the cutoff hold a person's Matrix id and serve nothing.
   *
   * @param cutoffMillis epoch milliseconds
   * @return number of deleted rows
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from MatrixCallObservationEnd e where e.endedAt < :cutoff")
  int deleteEndedBefore(@Param("cutoff") long cutoffMillis);
}
