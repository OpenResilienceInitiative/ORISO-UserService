package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.MatrixCallObservation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatrixCallObservationRepository
    extends JpaRepository<MatrixCallObservation, Long> {
  Optional<MatrixCallObservation> findBySourceRoomIdAndCallId(String sourceRoomId, String callId);

  /** Serialises renew and end of one call: a stale renew must never undo an end. */
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  Optional<MatrixCallObservation> findForUpdateBySourceRoomIdAndCallId(
      String sourceRoomId, String callId);

  Optional<MatrixCallObservation> findByMediaRoomId(String mediaRoomId);

  /**
   * Retention purge: removes observations whose lease and end are both older than the cutoff. A
   * live or recently ended call is never touched.
   *
   * @param cutoffMillis epoch milliseconds
   * @return number of deleted rows
   */
  @org.springframework.data.jpa.repository.Modifying(
      flushAutomatically = true,
      clearAutomatically = true)
  @org.springframework.data.jpa.repository.Query(
      "delete from MatrixCallObservation o where o.expiresAt < :cutoff "
          + "and (o.endedAt is null or o.endedAt < :cutoff)")
  int deleteFinishedBefore(
      @org.springframework.data.repository.query.Param("cutoff") long cutoffMillis);
}
