package de.caritas.cob.userservice.api.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A one-off Sprechstunde of a counsellor (ORISO-Frontend#2494): one dated window [start, end)
 * released for booking once, each slot bookable until {@code noticeMinutes} before its start. With
 * {@code overBlocker} the counsellor confirmed releasing it over their own blockers: those that lay
 * in it at that moment, kept as {@code releasedOver}; a blocker entered or moved later still takes
 * the time.
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

  /**
   * The counsellor's own blockers inside the window when it was released over them, as they were
   * then; empty without {@code overBlocker}. Read with the one-off, in one query for all one-offs
   * read together.
   */
  @ElementCollection(fetch = FetchType.EAGER)
  @Fetch(FetchMode.SUBSELECT)
  @CollectionTable(
      name = "calendar_consultation_once_blocker",
      joinColumns = @JoinColumn(name = "once_id", columnDefinition = "char(36)"))
  private Set<CalendarConsultationOnceBlocker> releasedOver = new HashSet<>();

  /** Whether this one-off was released over the blocker [start, end) of this id as it is now. */
  public boolean isReleasedOver(String blockerId, Instant start, Instant end) {
    return overBlocker && releasedOver.stream().anyMatch(b -> b.isUnchanged(blockerId, start, end));
  }
}
