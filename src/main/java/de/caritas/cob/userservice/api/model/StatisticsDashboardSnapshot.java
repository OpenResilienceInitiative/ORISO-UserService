package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One stored stand of the admin statistics dashboard: the numbers of one scope and tenant as they
 * were calculated for one cutoff. Admin roles read this row instead of the live data, so reloading
 * never shows a fresher number. The unique key allows exactly one stand per cutoff, whichever
 * replica writes first. Below-threshold rows hold no counselling numbers.
 */
@Entity
@Table(
    name = "statistics_dashboard_snapshot",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_statistics_dashboard_snapshot",
            columnNames = {"scope", "tenant_id", "snapshot_interval", "cutoff_at"}))
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString(exclude = "payload")
public class StatisticsDashboardSnapshot {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "scope", nullable = false, length = 16)
  private String scope;

  /** The platform-wide stand is stored under the technical tenant 0. */
  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "snapshot_interval", nullable = false, length = 16)
  private String snapshotInterval;

  /** The fixed time this stand belongs to, in UTC. */
  @Column(name = "cutoff_at", nullable = false, columnDefinition = "datetime")
  private LocalDateTime cutoffAt;

  /** When the numbers were actually calculated, in UTC. */
  @Column(name = "computed_at", nullable = false, columnDefinition = "datetime")
  private LocalDateTime computedAt;

  @Lob
  @Column(name = "payload", nullable = false, columnDefinition = "longtext")
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  private String payload;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof StatisticsDashboardSnapshot that)) {
      return false;
    }
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
