package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One temporary support session (ADR-018 §4): a fresh encrypted 1:1 Matrix room between a Global
 * Support Admin and a Berater*in, hard-limited to four hours and never reused — every further help
 * cycle starts completely from scratch.
 */
@Entity
@Table(
    name = "support_access_session",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_support_session_handshake", columnNames = "handshake_id"),
      @UniqueConstraint(name = "uk_support_session_active_lease", columnNames = "active_lease_key")
    })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportAccessSession {

  public enum SupportAccessSessionStatus {
    /** Confirmed, room being built. No access yet. */
    PROVISIONING,
    /** Room exists, membership verified, lease running. */
    ACTIVE,
    /** Access has been withdrawn logically; Matrix removal is not yet confirmed. */
    REVOCATION_PENDING,
    /** Matrix confirmed the withdrawal. Terminal. */
    CLOSED,
    /** Provisioning exhausted its attempts. Terminal, and visible in the Admin board. */
    PROVISIONING_FAILED;

    public boolean isTerminal() {
      return this == CLOSED || this == PROVISIONING_FAILED;
    }

    /** Everything except terminal states must be treated as "the GSA might still reach data". */
    public boolean isNonTerminal() {
      return !isTerminal();
    }
  }

  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "handshake_id", length = 36, nullable = false)
  private String handshakeId;

  @Column(name = "matrix_room_id", length = 255)
  private String matrixRoomId;

  /**
   * The Element Call media room registered by the client. Revocation has to close the signalling
   * room and this one, otherwise a call survives the four-hour cutoff.
   */
  @Column(name = "call_matrix_room_id", length = 255)
  private String callMatrixRoomId;

  @Column(name = "support_admin_id", length = 36, nullable = false)
  private String supportAdminId;

  /**
   * Matrix identity created for this session alone. It is deactivated on revocation and never
   * reused, so a later session cannot inherit membership or device keys from an earlier one.
   */
  @Column(name = "support_admin_matrix_id", length = 255)
  private String supportAdminMatrixId;

  @Column(name = "consultant_id", length = 36, nullable = false)
  private String consultantId;

  @Column(name = "agency_id")
  private Long agencyId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 24, nullable = false)
  private SupportAccessSessionStatus status;

  /**
   * Set to {@code supportAdmin:consultant:agency} while the session is non-terminal and to {@code
   * null} once it is terminal. The unique index therefore admits many closed sessions for a pair
   * but only ever one running one — the database, not application logic, is what enforces it.
   */
  @Column(name = "active_lease_key", length = 128)
  private String activeLeaseKey;

  @Column(name = "close_reason", length = 32)
  private String closeReason;

  @Builder.Default
  @Column(name = "provisioning_attempts", nullable = false)
  private int provisioningAttempts = 0;

  @Column(name = "last_error", length = 1000)
  private String lastError;

  @Column(name = "create_date", nullable = false)
  private LocalDateTime createDate;

  @Column(name = "expiry_date", nullable = false)
  private LocalDateTime expiryDate;

  /**
   * When the session entered REVOCATION_PENDING. The "withdrawal unproven for too long" alert has
   * to measure from here, not from creation — a four-hour-old session revoked a second ago is fine.
   */
  @Column(name = "revocation_started_date")
  private LocalDateTime revocationStartedDate;

  @Column(name = "closed_date")
  private LocalDateTime closedDate;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  /** The lease key exists exactly while the session is non-terminal. */
  public static String leaseKeyOf(String supportAdminId, String consultantId, Long agencyId) {
    return "%s:%s:%s".formatted(supportAdminId, consultantId, agencyId);
  }
}
