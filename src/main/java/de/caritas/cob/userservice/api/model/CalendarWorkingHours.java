package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One weekly window of a counsellor's working hours (ADR-032, contract addendum v1.1), in local
 * wall-clock time of the platform's zone. Display only: the calendar shades the time outside the
 * windows; booking, the subscription feed and the busy import do not look at them.
 */
@Entity
@Table(
    name = "calendar_working_hours",
    indexes = @Index(name = "idx_calendar_working_hours_consultant", columnList = "consultant_id"))
@Getter
@Setter
@NoArgsConstructor
public class CalendarWorkingHours {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(36)")
  private UUID id;

  @Column(name = "consultant_id", length = 36, nullable = false)
  private String consultantId;

  /** 0 = Sunday .. 6 = Saturday. */
  @Column(nullable = false)
  private int weekday;

  @Column(name = "start_time", nullable = false)
  private LocalTime startTime;

  @Column(name = "end_time", nullable = false)
  private LocalTime endTime;
}
