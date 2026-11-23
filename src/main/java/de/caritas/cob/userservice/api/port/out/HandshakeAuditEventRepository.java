package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.HandshakeAuditEvent;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface HandshakeAuditEventRepository extends CrudRepository<HandshakeAuditEvent, Long> {

  @Transactional
  @Modifying
  @Query(
      nativeQuery = true,
      value = "DELETE FROM handshake_audit_event WHERE create_date < :cutoff LIMIT :batchSize")
  int deleteBatchCreatedBefore(
      @Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
