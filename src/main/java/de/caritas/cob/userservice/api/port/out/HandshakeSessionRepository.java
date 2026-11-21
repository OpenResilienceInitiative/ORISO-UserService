package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface HandshakeSessionRepository extends CrudRepository<HandshakeSession, String> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select session from HandshakeSession session where session.id = :id")
  Optional<HandshakeSession> findByIdForUpdate(@Param("id") String id);

  List<HandshakeSession> findAllByCounterpartIdAndStatusAndExpiryDateAfter(
      String counterpartId, HandshakeStatus status, LocalDateTime after);

  List<HandshakeSession> findAllByStatusAndExpiryDateBefore(
      HandshakeStatus status, LocalDateTime before, org.springframework.data.domain.Pageable page);
}
