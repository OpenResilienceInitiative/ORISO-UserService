package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "handshake_outbox_event",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_handshake_outbox_aggregate_event",
            columnNames = {"aggregate_id", "event_type"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HandshakeOutboxEvent {

  public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "aggregate_id", length = 36, nullable = false)
  private String aggregateId;

  @Column(name = "event_type", length = 64, nullable = false)
  private String eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 16, nullable = false)
  private OutboxStatus status;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "create_date", nullable = false)
  private LocalDateTime createDate;

  @Column(name = "next_attempt_date", nullable = false)
  private LocalDateTime nextAttemptDate;

  @Column(name = "processed_date")
  private LocalDateTime processedDate;

  @Column(name = "last_error", length = 1000)
  private String lastError;

  @Version
  @Column(name = "version", nullable = false)
  private long version;
}
