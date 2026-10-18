package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

  @Enumerated(EnumType.STRING)
  @Column(name = "client_consent_mode", nullable = false, length = 20)
  private CaseHandoverConsentMode clientConsent;

  @Column(name = "access_allowed", nullable = false)
  private Boolean accessAllowed;

  @Column(name = "enabled", nullable = false)
  private Boolean enabled;

  @Column(name = "display_order", nullable = false)
  private Integer displayOrder;

  @Column(name = "policy_authority", nullable = false, length = 255)
  private String policyAuthority;

  /**
   * Client-facing notification templates per language (de/en/tr/uk), {{newAdvisor}} placeholder.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "client_notification_templates", columnDefinition = "json")
  private Map<String, String> clientNotificationTemplates;

  /** Required only for Advice Needed co-access; takeover reasons deliberately keep this null. */
  @Column(name = "max_access_duration_minutes")
  private Integer maxAccessDurationMinutes;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
}
