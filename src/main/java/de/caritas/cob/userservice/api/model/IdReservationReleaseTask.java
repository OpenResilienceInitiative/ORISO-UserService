package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseType;
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
import lombok.ToString;

/** Durable retry record for releasing an external tenant or agency ID reservation. */
@Entity
@Table(
    name = "id_reservation_release_task",
    uniqueConstraints =
        @UniqueConstraint(
            name = "idx_id_reservation_release_task_type_id",
            columnNames = {"allocation_type", "reserved_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class IdReservationReleaseTask {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "allocation_type", nullable = false, length = 16)
  private IdReservationReleaseType allocationType;

  @Column(name = "reserved_id", nullable = false)
  private Long reservedId;

  /** Tenant header needed when retrying an AgencyService release outside the request thread. */
  @Column(name = "tenant_context_id")
  private Long tenantContextId;

  @Column(name = "attempt_count", nullable = false)
  @Builder.Default
  private int attemptCount = 0;

  @Column(name = "last_attempt_at", columnDefinition = "datetime(6)")
  private LocalDateTime lastAttemptAt;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime(6)")
  private LocalDateTime createDate;
}
