package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.MatrixCallGuest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatrixCallGuestRepository extends JpaRepository<MatrixCallGuest, Long> {
  Optional<MatrixCallGuest> findByBindingIdAndMatrixUserId(Long bindingId, String matrixUserId);

  List<MatrixCallGuest> findByBindingIdAndRevokedAtIsNull(Long bindingId);

  boolean existsByBindingIdAndMatrixUserIdAndRevokedAtIsNull(Long bindingId, String matrixUserId);

  /** Ended calls whose guest seats were not all taken back yet (reconcile sweep, US#1477). */
  @org.springframework.data.jpa.repository.Query(
      "select b.mediaRoomId from MatrixCallBinding b where b.endedAt is not null and exists"
          + " (select g.id from MatrixCallGuest g where g.bindingId = b.id and g.revokedAt is null)"
          + " order by b.id")
  List<String> findEndedMediaRoomsWithOutstandingGuests(
      org.springframework.data.domain.Pageable page);
}
