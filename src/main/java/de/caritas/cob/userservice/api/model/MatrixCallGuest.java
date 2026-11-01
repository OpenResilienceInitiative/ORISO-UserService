package de.caritas.cob.userservice.api.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * A person admitted to one call without membership of its source conversation (US#1477). The
 * invitation reaches the call room only; {@code revokedAt} is set once the seat was taken back.
 */
@Entity
@Table(
    name = "matrix_call_guest",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_call_guest_binding_user",
            columnNames = {"binding_id", "matrix_user_id"}))
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatrixCallGuest {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "binding_id", nullable = false)
  private Long bindingId;

  @Column(name = "matrix_user_id", nullable = false, length = 255)
  private String matrixUserId;

  @Column(name = "admitted_by_matrix_id", nullable = false, length = 255)
  private String admittedByMatrixId;

  @Column(name = "admitted_at", nullable = false)
  private long admittedAt;

  @Column(name = "revoked_at")
  private Long revokedAt;

  /** Failed removals so far; the sweep retries the least recently tried rooms first. */
  @Column(name = "revoke_attempts", nullable = false)
  @Builder.Default
  private int revokeAttempts = 0;

  @Column(name = "last_revoke_attempt_at")
  private Long lastRevokeAttemptAt;

  public void recordFailedRevokeAttempt(long timestamp) {
    revokeAttempts++;
    lastRevokeAttemptAt = timestamp;
  }

  public void revoke(long timestamp) {
    if (revokedAt == null) revokedAt = timestamp;
  }
}
