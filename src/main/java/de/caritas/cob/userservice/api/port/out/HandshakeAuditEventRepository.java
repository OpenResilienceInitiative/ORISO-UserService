package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.HandshakeAuditEvent;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Audit trail of every support-access decision. The scoped finders exist so filtering happens in
 * SQL: an Agency Admin must not be able to widen their view by sending a different scope id.
 */
public interface HandshakeAuditEventRepository extends JpaRepository<HandshakeAuditEvent, Long> {

  void deleteAllByCreateDateBefore(LocalDateTime before);

  Page<HandshakeAuditEvent> findAllByOrderByCreateDateDesc(Pageable pageable);

  Page<HandshakeAuditEvent> findAllByTenantIdOrderByCreateDateDesc(
      Long tenantId, Pageable pageable);

  Page<HandshakeAuditEvent> findAllByAgencyIdInOrderByCreateDateDesc(
      Collection<Long> agencyIds, Pageable pageable);
}
