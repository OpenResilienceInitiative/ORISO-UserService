package de.caritas.cob.userservice.api.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** Durable rejection decision and immutable protocol-closure intent, retained with its case. */
@Entity
@Table(name = "enquiry_rejection")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
public class EnquiryRejection implements TenantAware {
  public enum State {
    PENDING,
    CONFIRMED
  }

  public enum RecipientOutcome {
    UNDECIDED,
    DELIVERED,
    SUPPRESSED
  }

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @Id
  @Column(name = "session_id", updatable = false)
  private Long sessionId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "session_id", insertable = false, updatable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Session session;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private Long tenantId;

  @Column(name = "actor_id", nullable = false, length = 36, updatable = false)
  private String actorId;

  @Column(name = "seeker_id", nullable = false, length = 64, updatable = false)
  private String seekerId;

  @Column(name = "agency_id", nullable = false, updatable = false)
  private Long agencyId;

  @Column(name = "primary_room_id", length = 255, updatable = false)
  private String primaryRoomId;

  @Column(name = "team_room_id", length = 255, updatable = false)
  private String teamRoomId;

  @Column(name = "generation", nullable = false, length = 36, updatable = false)
  private String generation;

  @Column(name = "rejected_at", nullable = false, updatable = false)
  private LocalDateTime rejectedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "closure_state", nullable = false, length = 20)
  private State state;

  @Enumerated(EnumType.STRING)
  @Column(name = "recipient_outcome", nullable = false, length = 20)
  private RecipientOutcome recipientOutcome;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "last_attempt_at")
  private LocalDateTime lastAttemptAt;

  @Column(name = "next_attempt_at", nullable = false)
  private LocalDateTime nextAttemptAt;

  @Column(name = "claim_token", length = 36)
  private String claimToken;

  @Column(name = "claim_until")
  private LocalDateTime claimUntil;

  @Column(name = "failure_stage", length = 32)
  private String failureStage;

  @Column(name = "confirmed_at")
  private LocalDateTime confirmedAt;
}
