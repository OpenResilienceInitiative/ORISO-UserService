package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

/**
 * A counsellor's proposal to move a colleague's 1:1 appointment (ADR-032, move proposals v1). The
 * colleague whose conversation it is decides. Times and references only: no asker, no content.
 *
 * <p>{@code entryStart} and {@code entryEnd} keep the appointment's time when the proposal was
 * made: once the appointment has another time, the proposal is void. The row goes with its
 * appointment, and with the account of either counsellor.
 */
@Entity
@Table(
    name = "calendar_move_proposal",
    indexes = {
      @Index(name = "idx_calendar_move_proposal_approver", columnList = "approver_id, status"),
      @Index(name = "idx_calendar_move_proposal_proposer", columnList = "proposer_id, status"),
      @Index(name = "idx_calendar_move_proposal_appointment", columnList = "appointment_id, status")
    })
@Getter
@Setter
@NoArgsConstructor
public class CalendarMoveProposal {

  public enum Status {
    OPEN,
    ACCEPTED,
    DECLINED,
    WITHDRAWN,
    SUPERSEDED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(36)")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "appointment_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_calendar_move_proposal_appointment"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private SessionAppointment appointment;

  @Column(name = "proposer_id", length = 36, nullable = false)
  private String proposerId;

  @Column(name = "approver_id", length = 36, nullable = false)
  private String approverId;

  @Column(name = "proposed_start", nullable = false)
  private Instant proposedStart;

  @Column(name = "proposed_end", nullable = false)
  private Instant proposedEnd;

  @Column(name = "entry_start", nullable = false)
  private Instant entryStart;

  @Column(name = "entry_end", nullable = false)
  private Instant entryEnd;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private Status status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "decided_at")
  private Instant decidedAt;

  /** Why the proposer asks (ORISO-UserService#1663); null when they gave none. */
  @Column(length = 500)
  private String reason;
}
