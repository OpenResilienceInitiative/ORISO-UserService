package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.HandshakeAuditEvent;
import java.time.LocalDateTime;
import org.springframework.data.repository.CrudRepository;

public interface HandshakeAuditEventRepository extends CrudRepository<HandshakeAuditEvent, Long> {

  void deleteAllByCreateDateBefore(LocalDateTime before);
}
