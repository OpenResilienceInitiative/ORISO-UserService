package de.caritas.cob.userservice.api.model;

import java.time.LocalDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "case_handover_reason_policy")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class CaseHandoverReasonPolicy {

  @Id
  @Column(name = "code", nullable = false, length = 100)
  private String code;

  @Column(name = "label", nullable = false, length = 255)
  private String label;

  @Column(name = "client_consent_required", nullable = false)
  private Boolean clientConsentRequired;

  @Column(name = "access_allowed", nullable = false)
  private Boolean accessAllowed;

  @Column(name = "enabled", nullable = false)
  private Boolean enabled;

  @Column(name = "display_order", nullable = false)
  private Integer displayOrder;

  @Column(name = "policy_authority", nullable = false, length = 255)
  private String policyAuthority;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
}
