package de.caritas.cob.userservice.api.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

/** Receipt and authorized provenance, never a human token or password. */
@Entity
@Table(name = "identity_creation_attempt")
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = {"creationProof", "provenance"})
public class IdentityCreationAttempt {
  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "request_key", unique = true, length = 64)
  private String requestKey;

  @Column(name = "account_id", length = 64, unique = true)
  private String accountId;

  @Column(name = "creation_proof", length = 256)
  private String creationProof;

  @Column(name = "origin_kind", nullable = false, length = 32)
  private String originKind;

  @Column(name = "registration_kind", nullable = false, length = 32)
  private String registrationKind;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "initial_roles", nullable = false, length = 512)
  private String initialRoles;

  @Column(name = "authorized_agency_ids", length = 512)
  private String authorizedAgencyIds;

  @Column(nullable = false, length = 128)
  private String provenance;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "execution_claim", length = 36)
  private String executionClaim;

  @Column(name = "execution_expires_at")
  private LocalDateTime executionExpiresAt;

  @Column(name = "bootstrap_session_id")
  private Long bootstrapSessionId;

  @Column(name = "bootstrap_expires_at")
  private LocalDateTime bootstrapExpiresAt;

  @Column(name = "bootstrap_failed_at")
  private LocalDateTime bootstrapFailedAt;

  @Column(name = "update_date", nullable = false)
  private LocalDateTime updateDate;
}
