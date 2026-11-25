package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One of the counsellor's own blockers a one-off Sprechstunde was released over
 * (ORISO-Frontend#2494): the blocker as it was when the counsellor confirmed releasing the one-off
 * over it. Only such a blocker gives way to the one-off; a blocker entered later, or this one moved
 * since, still takes the time.
 */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CalendarConsultationOnceBlocker {

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "blocker_id", columnDefinition = "char(36)", nullable = false)
  private UUID blockerId;

  @Column(name = "start_time", nullable = false)
  private Instant startTime;

  @Column(name = "end_time", nullable = false)
  private Instant endTime;

  /** The blocker as it is now, in the precision the database keeps. */
  public static CalendarConsultationOnceBlocker of(CalendarBlocker blocker) {
    return new CalendarConsultationOnceBlocker(
        blocker.getId(), micros(blocker.getStartTime()), micros(blocker.getEndTime()));
  }

  /** Whether the blocker [start, end) of this id is this one, unmoved. */
  public boolean isUnchanged(String id, Instant start, Instant end) {
    return blockerId.toString().equals(id)
        && startTime.equals(micros(start))
        && endTime.equals(micros(end));
  }

  private static Instant micros(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS);
  }
}
