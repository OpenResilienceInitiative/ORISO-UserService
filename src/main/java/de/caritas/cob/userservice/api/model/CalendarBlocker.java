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
 * Time a counsellor is not available (ADR-032). {@link Source#ORISO} is a blocker the counsellor
 * entered here, optionally with a note and as part of a series; {@link Source#EXTERNAL} is a busy
 * time imported from the counsellor's own calendar, of which only start and end are kept.
 */
@Entity
@Table(
    name = "calendar_blocker",
    indexes =
        @Index(
            name = "idx_calendar_blocker_consultant_start",
            columnList = "consultant_id, start_time"))
@Getter
@Setter
@NoArgsConstructor
public class CalendarBlocker {

  public enum Source {
    ORISO,
    EXTERNAL
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(36)")
  private UUID id;

  @Column(name = "consultant_id", length = 36, nullable = false)
  private String consultantId;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "start_time", nullable = false)
  private Instant startTime;

  @Column(name = "end_time", nullable = false)
  private Instant endTime;

  @Column(name = "all_day", nullable = false)
  private boolean allDay;

  @Column(length = 255)
  private String note;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "series_id", columnDefinition = "char(36)")
  private UUID seriesId;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private Source source;

  @Column(name = "create_date", nullable = false)
  private Instant createDate;
}
