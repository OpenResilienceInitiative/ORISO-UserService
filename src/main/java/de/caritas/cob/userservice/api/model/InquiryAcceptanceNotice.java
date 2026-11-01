package de.caritas.cob.userservice.api.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

/** One immutable initial-acceptance fact and its durable Matrix delivery state. */
@Entity
@Table(name = "inquiry_acceptance_notice")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InquiryAcceptanceNotice {
  public enum DeliveryState {
    PREPARING,
    PENDING,
    UNCERTAIN,
    SENT
  }

  @Id
  @Column(name = "session_id")
  private Long sessionId;

  @Column(name = "owner_id", nullable = false, updatable = false)
  private String ownerId;

  @Column(name = "ownership_revision", nullable = false, updatable = false)
  private long ownershipRevision;

  @Column(name = "accepted_at_utc", nullable = false, updatable = false)
  private LocalDateTime acceptedAtUtc;

  @Column(name = "matrix_room_id")
  private String matrixRoomId;

  @Column(name = "sender_matrix_id")
  private String senderMatrixId;

  @Column(name = "public_advisor_name", updatable = false)
  private String publicAdvisorName;

  @Column(name = "title", nullable = false, updatable = false)
  private String title;

  @Column(name = "description", nullable = false, updatable = false, length = 1000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "delivery_state", nullable = false)
  private DeliveryState deliveryState;

  @Column(name = "next_attempt_at_utc")
  private LocalDateTime nextAttemptAtUtc;

  @Column(name = "matrix_event_id")
  private String matrixEventId;
}
