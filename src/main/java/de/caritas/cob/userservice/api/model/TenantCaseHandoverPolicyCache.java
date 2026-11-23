package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Last-known-good tenant policy snapshot owned by TenantService. */
@Entity
@Table(name = "tenant_case_handover_policy_cache")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantCaseHandoverPolicyCache {

  @Id
  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "policies", nullable = false, columnDefinition = "LONGTEXT")
  private String policies;

  @Column(name = "refreshed_at", nullable = false)
  private LocalDateTime refreshedAt;

  @Column(name = "stale_since")
  private LocalDateTime staleSince;
}
