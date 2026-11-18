package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An appointment between two counsellors (ORISO-Frontend#2305): no advice seeker and no counselling
 * conversation. It belongs to the private internal chat of the two, which is where they meet for
 * {@code VIDEO} and {@code CHAT}.
 */
@Entity
@Table(
    name = "calendar_colleague_appointment",
    indexes = {
      @Index(
          name = "idx_calendar_colleague_appointment_organizer",
          columnList = "organizer_id, start_time"),
      @Index(
          name = "idx_calendar_colleague_appointment_invitee",
          columnList = "invitee_id, start_time")
    })
@Getter
@Setter
@NoArgsConstructor
public class CalendarColleagueAppointment {

  public enum Status {
    /** Waits for the invited counsellor. */
    PENDING,
    CONFIRMED,
    DECLINED,
    CANCELLED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(36)")
  private UUID id;

  @Column(name = "organizer_id", length = 36, nullable = false)
  private String organizerId;

  @Column(name = "invitee_id", length = 36, nullable = false)
  private String inviteeId;

  /** The internal chat of the two; kept when the chat is gone, the appointment still stands. */
  @Column(name = "chat_id")
  private Long chatId;

  @Column(name = "matrix_room_id")
  private String matrixRoomId;

  @Column(name = "start_time", nullable = false)
  private Instant startTime;

  @Column(name = "end_time", nullable = false)
  private Instant endTime;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private SessionAppointment.Kind kind;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private Status status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public boolean involves(String consultantId) {
    return organizerId.equals(consultantId) || inviteeId.equals(consultantId);
  }

  /** The counsellor on the other side, seen from {@code consultantId}. */
  public String otherThan(String consultantId) {
    return organizerId.equals(consultantId) ? inviteeId : organizerId;
  }
}
