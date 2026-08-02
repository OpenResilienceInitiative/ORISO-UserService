package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface HandshakeOutboxEventRepository extends JpaRepository<HandshakeOutboxEvent, Long> {

  List<HandshakeOutboxEvent> findAllByStatusAndNextAttemptDateBeforeOrderById(
      OutboxStatus status, LocalDateTime before, Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "UPDATE HandshakeOutboxEvent e SET e.status = 'PROCESSING' "
          + "WHERE e.id = :id AND e.status = 'PENDING'")
  int claim(@Param("id") Long id);

  /** Keeps job creation idempotent alongside the unique (aggregate_id, event_type) index. */
  boolean existsByAggregateIdAndEventType(String aggregateId, String eventType);

  long countByStatus(OutboxStatus status);

  List<HandshakeOutboxEvent> findAllByStatusOrderByCreateDateAsc(
      OutboxStatus status, Pageable pageable);
}
