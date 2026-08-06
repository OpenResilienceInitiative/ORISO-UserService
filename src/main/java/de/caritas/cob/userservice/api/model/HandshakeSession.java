package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One live-handshake cycle (ADR-018): initiated with fresh password+OTP, confirmable by exactly one
 * counterpart with a fresh password inside the TTL window, single-use.
 */
@Entity
@Table(name = "handshake_session")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HandshakeSession {

  public enum HandshakeStatus {
    PENDING,
    CONFIRMED,
    EXPIRED
  }

  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Enumerated(EnumType.STRING)
  @Column(name = "purpose", length = 40, nullable = false)
  private HandshakePurpose purpose;

  @Column(name = "initiator_id", length = 36, nullable = false)
  private String initiatorId;

  @Column(name = "counterpart_id", length = 36, nullable = false)
  private String counterpartId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 16, nullable = false)
  private HandshakeStatus status;

  @Column(name = "create_date", nullable = false)
  private LocalDateTime createDate;

  @Column(name = "expiry_date", nullable = false)
  private LocalDateTime expiryDate;

  @Column(name = "confirmed_date")
  private LocalDateTime confirmedDate;

  @Column(name = "tenant_id")
  private Long tenantId;

  /** Brute-force guard: failed counterpart password attempts; at the limit the session locks. */
  @Builder.Default
  @Column(name = "confirm_attempts", nullable = false)
  private int confirmAttempts = 0;

  /** Optimistic lock: exactly one concurrent confirmation may win the PENDING→CONFIRMED race. */
  @jakarta.persistence.Version
  @Column(name = "version", nullable = false)
  private long version;
}
