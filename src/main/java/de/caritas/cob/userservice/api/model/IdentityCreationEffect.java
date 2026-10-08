package de.caritas.cob.userservice.api.model;

import jakarta.persistence.*;

/**
 * Schema mapping only; independently committed child references intentionally have no parent FK.
 */
@Entity
@Table(
    name = "identity_creation_effect",
    indexes = @Index(name = "idx_creation_effect_attempt", columnList = "attempt_id"))
public class IdentityCreationEffect {
  @Id
  @Column(length = 36, nullable = false)
  private String id;

  @Column(name = "attempt_id", length = 36, nullable = false)
  private String attemptId;

  @Column(name = "account_id", length = 64, nullable = false)
  private String accountId;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "execution_claim", length = 36, nullable = false)
  private String executionClaim;

  @Column(name = "effect_kind", length = 32, nullable = false)
  private String effectKind;

  @Column(length = 32, nullable = false)
  private String state;

  @Column(name = "requested_target", length = 512, nullable = false)
  private String requestedTarget;

  @Column(name = "target_id", length = 512)
  private String targetId;

  @Column(length = 48)
  private String provenance;
}
