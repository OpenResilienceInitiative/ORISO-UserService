package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

/**
 * A 1:1 appointment of one conversation (ADR-020). Owned by UserService alone. Holds times and
 * references only.
 */
@Entity
@Table(name = "session_appointment")
@Getter
@Setter
@NoArgsConstructor
public class SessionAppointment {

  /**
   * REQUESTED: an advice seeker's booking that waits for the counsellor (ORISO-Frontend#2287); it
   * takes the time like a scheduled one. Confirmed it is SCHEDULED, declined DECLINED. A request
   * nobody answered by its start is declined automatically (Frank, 17 Nov 2026).
   */
  public enum Status {
    SCHEDULED,
    CANCELLED,
    REQUESTED,
    DECLINED
  }

  /** How the appointment takes place (ADR-032); null on rows made before the kind existed. */
  public enum Kind {
    ONSITE,
    VIDEO,
    PHONE,
    CHAT
  }

  @Id
  @GeneratedValue(generator = "UUID")
  @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(36)")
  private UUID id;

  /**
   * The conversation. Empty only for the appointment of a direct invitation nobody opened yet
   * (ORISO-Frontend#2252): the first person who opens it creates the appointment conversation.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "session_id",
      foreignKey = @ForeignKey(name = "fk_session_appointment_session"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Session session;

  @Column(name = "start_time", nullable = false)
  private Instant startTime;

  @Column(name = "end_time", nullable = false)
  private Instant endTime;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private Status status;

  @Enumerated(EnumType.STRING)
  @Column(length = 16)
  private Kind kind;

  /** A request declined automatically because nobody answered it by its start (#2287). */
  @Column(name = "auto_declined", nullable = false)
  private boolean autoDeclined;

  /**
   * The conversation notice of an automatic decline is still to be posted. The backend holds no
   * room keys, so the first participant's client that sees the flag claims and posts it.
   */
  @Column(name = "notice_pending", nullable = false)
  private boolean noticePending;
}
