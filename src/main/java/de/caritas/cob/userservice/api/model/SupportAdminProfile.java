package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Operational state of a Global Support Admin (ADR-018). This row, not the bearer token, decides
 * whether a GSA may act: an access token issued before a disable is worthless because every GSA
 * endpoint re-reads this status.
 */
@Entity
@Table(name = "support_admin_profile")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportAdminProfile {

  public enum SupportAdminStatus {
    /** Account row exists; Keycloak identity is still disabled and unprivileged. */
    INVITED,
    /** Provisioned and released, but no second factor enrolled yet — cannot start support. */
    PENDING_2FA,
    /** Fully usable. */
    ACTIVE,
    /** Disable requested; running sessions are being revoked. */
    DISABLING,
    /** Blocked. New handshakes are refused and existing access has been withdrawn. */
    DISABLED,
    /** Provisioning failed; the account was never released and must not be usable. */
    PROVISIONING_FAILED;

    /** Only an ACTIVE profile may initiate or hold support access. */
    public boolean isOperational() {
      return this == ACTIVE;
    }

    public boolean isTerminalBlock() {
      return this == DISABLED || this == PROVISIONING_FAILED;
    }
  }

  @Id
  @Column(name = "admin_id", length = 36, nullable = false)
  private String adminId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 24, nullable = false)
  private SupportAdminStatus status;

  @Builder.Default
  @Column(name = "provisioning_attempts", nullable = false)
  private int provisioningAttempts = 0;

  @Column(name = "last_error", length = 1000)
  private String lastError;

  @Column(name = "create_date", nullable = false)
  private LocalDateTime createDate;

  @Column(name = "update_date", nullable = false)
  private LocalDateTime updateDate;

  @Column(name = "disabled_date")
  private LocalDateTime disabledDate;

  @Version
  @Column(name = "version", nullable = false)
  private long version;
}
