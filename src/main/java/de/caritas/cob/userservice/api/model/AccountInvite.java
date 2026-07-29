package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(
    name = "account_invite",
    indexes = {
      @Index(name = "idx_account_invite_tenant_status", columnList = "tenant_id,status"),
      @Index(name = "idx_account_invite_target_role", columnList = "target_role"),
      @Index(name = "idx_account_invite_token_hash", columnList = "token_hash", unique = true)
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"tokenHash", "tenantIdReservationToken", "totpPendingSecret"})
public class AccountInvite {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_role", nullable = false, length = 64)
  private AccountInviteTargetRole targetRole;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "recipient_email", nullable = false)
  private String recipientEmail;

  @Column(name = "first_name")
  private String firstName;

  @Column(name = "last_name")
  private String lastName;

  @Column(name = "agency_id")
  private Long agencyId;

  /**
   * Token of the TenantService tenant-ID reservation held by this invite (TEN-INV-U3). Tenant
   * creation consumes the reserved ID only against this token; it stays with the invite until the
   * reservation is consumed or released.
   */
  @Column(name = "tenant_id_reservation_token", length = 36)
  private String tenantIdReservationToken;

  @Column(name = "department_id")
  private Long departmentId;

  @Column(name = "token_hash", length = 64)
  private String tokenHash;

  @Column(name = "expires_at", columnDefinition = "datetime")
  private LocalDateTime expiresAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  @Builder.Default
  private AccountInviteStatus status = AccountInviteStatus.DRAFT;

  @Enumerated(EnumType.STRING)
  @Column(name = "email_verification_status", nullable = false, length = 32)
  @Builder.Default
  private EmailVerificationStatus emailVerificationStatus = EmailVerificationStatus.PENDING;

  @Enumerated(EnumType.STRING)
  @Column(name = "two_factor_status", nullable = false, length = 32)
  @Builder.Default
  private TwoFactorGateStatus twoFactorStatus = TwoFactorGateStatus.NOT_REQUIRED;

  @Column(name = "accepted_at", columnDefinition = "datetime")
  private LocalDateTime acceptedAt;

  @Column(name = "accepted_by_user_id", length = 36)
  private String acceptedByUserId;

  @Column(name = "revoked_at", columnDefinition = "datetime")
  private LocalDateTime revokedAt;

  @Column(name = "revoked_by_user_id", length = 36)
  private String revokedByUserId;

  @Column(name = "superseded_at", columnDefinition = "datetime")
  private LocalDateTime supersededAt;

  @Column(name = "superseded_by_user_id", length = 36)
  private String supersededByUserId;

  @Column(name = "superseded_by_invite_id")
  private Long supersededByInviteId;

  /**
   * TOTP secret issued to the invitee during the public tenant-admin onboarding (#569 chain fix).
   * The public two-factor endpoint only receives the one-time password, so the secret shown at
   * registration must be kept server-side until the activation succeeds; it is cleared once the OTP
   * credential exists. Only ever set for invites onboarded through the public onboarding endpoints.
   */
  @Column(name = "totp_pending_secret", length = 64)
  private String totpPendingSecret;

  @Column(name = "two_factor_waived_by", length = 36)
  private String twoFactorWaivedBy;

  @Column(name = "two_factor_waived_at", columnDefinition = "datetime")
  private LocalDateTime twoFactorWaivedAt;

  @Column(name = "two_factor_waiver_reason", length = 512)
  private String twoFactorWaiverReason;

  @Column(name = "created_by_user_id", length = 36)
  private String createdByUserId;

  @Column(name = "created_by_username")
  private String createdByUsername;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime")
  private LocalDateTime createDate;

  @Column(name = "update_date", columnDefinition = "datetime")
  private LocalDateTime updateDate;
}
