package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.IdReservationLock;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseType;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdReservationLockRepository
    extends JpaRepository<IdReservationLock, IdReservationLock.Key> {

  /** Only for a row that exists: a locking read of a missing key would take a gap lock. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT l FROM IdReservationLock l"
          + " WHERE l.allocationType = :type AND l.reservedId = :reservedId")
  Optional<IdReservationLock> findForUpdate(
      @Param("type") IdReservationReleaseType type, @Param("reservedId") Long reservedId);
}
