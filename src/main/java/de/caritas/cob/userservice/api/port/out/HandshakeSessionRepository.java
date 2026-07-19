package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface HandshakeSessionRepository extends CrudRepository<HandshakeSession, String> {

  List<HandshakeSession> findAllByCounterpartIdAndStatusAndExpiryDateAfter(
      String counterpartId, HandshakeStatus status, LocalDateTime after);

  List<HandshakeSession> findAllByStatusAndExpiryDateBefore(
      HandshakeStatus status, LocalDateTime before, org.springframework.data.domain.Pageable page);
}
