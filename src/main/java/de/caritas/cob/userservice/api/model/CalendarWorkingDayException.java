package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One working window of a counsellor on one date (ADR-032, contract addendum v1.2), in local
 * wall-clock time of the platform's zone. The windows of a date take the place of the weekly
 * windows and of a public holiday on that date. A whole day off is one row without start and end.
 * Display only, like the weekly working hours.
 */
@Entity
@Table(
    name = "calendar_working_day_exception",
    indexes =
        @Index(
            name = "idx_calendar_working_day_exception_consultant_date",
            columnList = "consultant_id, exception_date"))
@Getter
@Setter
@NoArgsConstructor
public class CalendarWorkingDayException {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(36)")
  private UUID id;

  @Column(name = "consultant_id", length = 36, nullable = false)
  private String consultantId;

  @Column(name = "exception_date", nullable = false)
  private LocalDate exceptionDate;

  /** Null together with {@link #endTime} on the row that makes the whole day off. */
  @Column(name = "start_time")
  private LocalTime startTime;

  @Column(name = "end_time")
  private LocalTime endTime;

  public boolean isDayOff() {
    return startTime == null || endTime == null;
  }
}
