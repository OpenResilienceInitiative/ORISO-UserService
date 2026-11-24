package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A one-off Sprechstunde of a counsellor (ORISO-Frontend#2494): one dated window [start, end)
 * released for booking once, each slot bookable until {@code noticeMinutes} before its start. With
 * {@code overBlocker} the counsellor confirmed releasing it over their own blockers.
 */
@Entity
@Table(
    name = "calendar_consultation_once",
    indexes =
        @Index(
            name = "idx_calendar_consultation_once_consultant_end",
            columnList = "consultant_id, end_at"))
@Getter
@Setter
@NoArgsConstructor
public class CalendarConsultationOnce {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(36)")
  private UUID id;

  @Column(name = "consultant_id", length = 36, nullable = false)
  private String consultantId;

  @Column(name = "start_at", nullable = false)
  private Instant startAt;

  @Column(name = "end_at", nullable = false)
  private Instant endAt;

  @Column(name = "notice_minutes", nullable = false)
  private int noticeMinutes;

  @Column(name = "over_blocker", nullable = false)
  private boolean overBlocker;
}
