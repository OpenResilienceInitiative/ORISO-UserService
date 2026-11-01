package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.MatrixCallGuest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatrixCallGuestRepository extends JpaRepository<MatrixCallGuest, Long> {
  Optional<MatrixCallGuest> findByBindingIdAndMatrixUserId(Long bindingId, String matrixUserId);

  List<MatrixCallGuest> findByBindingIdAndRevokedAtIsNull(Long bindingId);

  boolean existsByBindingIdAndMatrixUserIdAndRevokedAtIsNull(Long bindingId, String matrixUserId);

  /**
   * Ended calls whose guest seats were not all taken back yet (reconcile sweep, US#1477), skipping
   * seats tried after {@code retryBefore}. Never tried first, then least recently tried, so rooms
   * that keep failing cannot starve later ones.
   */
  @org.springframework.data.jpa.repository.Query(
      "select b.mediaRoomId from MatrixCallBinding b, MatrixCallGuest g"
          + " where g.bindingId = b.id and b.endedAt is not null and g.revokedAt is null"
          + " and (g.lastRevokeAttemptAt is null or g.lastRevokeAttemptAt <= :retryBefore)"
          + " group by b.mediaRoomId"
          + " order by coalesce(max(g.lastRevokeAttemptAt), 0), b.mediaRoomId")
  List<String> findEndedMediaRoomsWithOutstandingGuests(
      @org.springframework.data.repository.query.Param("retryBefore") long retryBefore,
      org.springframework.data.domain.Pageable page);
}
