package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Delivery evidence keyed by the recipient and Matrix event, without mail content or address. */
@Entity
@Table(name = "reply_email_delivery")
@Getter
@Setter
@NoArgsConstructor
public class ReplyEmailDelivery {
  public enum Status {
    RESERVED,
    SENT,
    REJECTED,
    UNCERTAIN
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "recipient_user_id", nullable = false, length = 36)
  private String recipientUserId;

  @Column(name = "event_key", nullable = false, length = 64)
  private String eventKey;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private Status status;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "sent_at")
  private LocalDateTime sentAt;
}
