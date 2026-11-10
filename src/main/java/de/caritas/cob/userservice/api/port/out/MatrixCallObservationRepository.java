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
}
