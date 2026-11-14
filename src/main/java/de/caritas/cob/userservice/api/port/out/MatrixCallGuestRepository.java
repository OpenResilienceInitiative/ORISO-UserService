package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.MatrixCallGuest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatrixCallGuestRepository extends JpaRepository<MatrixCallGuest, Long> {
  Optional<MatrixCallGuest> findByBindingIdAndMatrixUserId(Long bindingId, String matrixUserId);

  List<MatrixCallGuest> findByBindingIdAndRevokedAtIsNull(Long bindingId);

  /**
   * FE#2209 round 4: the seats not yet taken back, as a locking read. Under the binding's row lock
   * it sees every seat committed before, also one a join committed while the caller waited on that
   * lock, which a plain read of the transaction's REPEATABLE READ snapshot would miss.
   */
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query(
      "select g from MatrixCallGuest g where g.bindingId = :bindingId and g.revokedAt is null"
          + " order by g.id")
  List<MatrixCallGuest> lockOutstandingByBindingId(
      @org.springframework.data.repository.query.Param("bindingId") Long bindingId);

  List<MatrixCallGuest> findByBindingId(Long bindingId);

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
