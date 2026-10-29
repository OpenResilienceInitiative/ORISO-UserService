package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable evidence for a tracked native account creation attempt. */
@Entity
@Table(name = "account_creation_attempt")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountCreationAttempt {
  public enum Status {
    PENDING,
    NATIVE_ID_KNOWN
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(
      name = "operation_key",
      columnDefinition = "char(36)",
      nullable = false,
      updatable = false,
      unique = true)
  private UUID operationKey;

  @Column(name = "owner_id", nullable = false, updatable = false, length = 255)
  private String ownerId;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private Long tenantId;

  @Column(name = "native_identity_id", length = 255)
  private String nativeIdentityId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  // Nullable on the retained storage-foundation rows: migration never grants dispatch.
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "receipt_id", columnDefinition = "char(36)", unique = true, updatable = false)
  private UUID receiptId;

  @Column(name = "agency_ids", columnDefinition = "LONGTEXT", updatable = false)
  private String agencyIds;

  @Column(name = "creation_role", length = 32, updatable = false)
  private String creationRole;

  @Column(name = "fingerprint_version", updatable = false)
  private Short fingerprintVersion;

  @Column(name = "request_fingerprint", length = 64, updatable = false)
  private String requestFingerprint;

  @Column(name = "receipt_version")
  private Long receiptVersion;

  @Column(name = "dispatched_at")
  private LocalDateTime dispatchedAt;

  @Column(name = "captured_at")
  private LocalDateTime capturedAt;

  @Column(length = 32)
  private String dispatch;

  @Column(length = 32)
  private String workflow;

  @Column(name = "consultant_row", length = 32)
  private String consultantRow;

  @Column(length = 32)
  private String onboarding;

  @Column(length = 32)
  private String compensation;

  @Column(length = 64)
  private String reason;
}
