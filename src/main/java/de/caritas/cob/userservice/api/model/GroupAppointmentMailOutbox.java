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
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** A single recipient's durable, revisioned appointment-mail claim. */
@Entity
@Table(
    name = "group_appointment_mail_outbox",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_gamo_delivery",
            columnNames = {
              "series_id",
              "occurrence_index",
              "occurrence_revision",
              "event_type",
              "recipient_role",
              "recipient_id"
            }))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "recipientId")
public class GroupAppointmentMailOutbox {
  public enum EventType {
    CONFIRMED,
    RESCHEDULED,
    CANCELLED,
    REMINDER
  }

  public enum Status {
    PENDING,
    SENDING,
    SENT,
    SUPPRESSED,
    UNCERTAIN
  }

  public enum RecipientRole {
    PARTICIPANT,
    COUNSELOR
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "series_id", nullable = false)
  private Long seriesId;

  @Column(name = "occurrence_index", nullable = false)
  private int occurrenceIndex;

  @Column(name = "occurrence_revision", nullable = false)
  private long occurrenceRevision;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 16)
  private EventType eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "recipient_role", nullable = false, length = 16)
  private RecipientRole recipientRole;

  @Column(name = "recipient_id", nullable = false, length = 36)
  private String recipientId;

  /** Opaque stable message header for reconciling an ambiguous transport handoff. */
  @Column(name = "correlation_id", nullable = false, length = 36, unique = true)
  private String correlationId;

  @Column(name = "scheduled_start_utc")
  private LocalDateTime scheduledStartUtc;

  @Column(name = "timezone", nullable = false, length = 64)
  private String timezone;

  @Column(name = "due_at_utc", nullable = false)
  private LocalDateTime dueAtUtc;

  @Column(name = "next_attempt_at_utc", nullable = false)
  private LocalDateTime nextAttemptAtUtc;

  @Column(name = "failure_count", nullable = false)
  private int failureCount;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private Status status;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "claimed_at")
  private LocalDateTime claimedAt;

  @Column(name = "sent_at")
  private LocalDateTime sentAt;
}
