package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Stable occurrence identity and monotonic revision, even when its scheduled time changes. */
@Entity
@Table(
    name = "group_appointment_occurrence_state",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_gaos_series_occurrence",
            columnNames = {"series_id", "occurrence_index"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupAppointmentOccurrenceState {
  public enum Status {
    ACTIVE,
    CANCELLED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "series_id", nullable = false)
  private Long seriesId;

  @Column(name = "occurrence_index", nullable = false)
  private int occurrenceIndex;

  @Column(name = "revision", nullable = false)
  private long revision;

  @Column(name = "original_start_utc", nullable = false)
  private LocalDateTime originalStartUtc;

  @Column(name = "effective_start_utc")
  private LocalDateTime effectiveStartUtc;

  @Column(name = "timezone", nullable = false, length = 64)
  private String timezone;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private Status status;
}
