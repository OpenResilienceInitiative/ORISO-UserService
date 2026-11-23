package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A durable release of one specific reservation, independent of the invitation's lifetime. */
@Entity
@Table(
    name = "tenant_reservation_release_task",
    uniqueConstraints =
        @UniqueConstraint(
            name = "idx_tenant_reservation_release_generation",
            columnNames = {"tenant_id", "release_generation"}))
@Getter
@NoArgsConstructor
public class TenantReservationReleaseTask {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "reservation_token", nullable = false, length = 36)
  private String reservationToken;

  @Column(name = "release_generation", nullable = false, length = 64)
  private String releaseGeneration;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "last_attempt_at", columnDefinition = "datetime(6)")
  private LocalDateTime lastAttemptAt;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime(6)")
  private LocalDateTime createDate;

  public TenantReservationReleaseTask(
      Long tenantId, String reservationToken, String releaseGeneration, LocalDateTime createDate) {
    this.tenantId = tenantId;
    this.reservationToken = reservationToken;
    this.releaseGeneration = releaseGeneration;
    this.createDate = createDate;
  }

  public void recordFailedAttempt(LocalDateTime now) {
    attemptCount++;
    lastAttemptAt = now;
  }
}
