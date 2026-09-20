package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Latest accepted state for one device; departures retain its historical attendance. */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MatrixCallDevice {
  @Column(name = "sender_matrix_id", nullable = false, length = 255)
  private String senderMatrixId;

  @Column(name = "event_timestamp", nullable = false)
  private long eventTimestamp;

  @Column(name = "expires_at", nullable = false)
  private long expiresAt;

  @Column(name = "attended", nullable = false)
  private boolean attended;

  @Column(name = "event_id", nullable = false, length = 255)
  private String eventId;
}
