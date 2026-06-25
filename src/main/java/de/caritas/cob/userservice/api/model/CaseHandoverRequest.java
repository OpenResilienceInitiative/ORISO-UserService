package de.caritas.cob.userservice.api.model;

import java.time.LocalDateTime;
import java.util.Objects;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/** Audit and policy record for a counsellor requesting access to an already existing case. */
@Entity
@Table(name = "case_handover_request")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
@FilterDef(
    name = "tenantFilter",
    parameters = {@ParamDef(name = "tenantId", type = "long")})
@Filter(
    name = "tenantFilter",
    condition = "(tenant_id = :tenantId OR (:tenantId = 1 AND tenant_id IS NULL))")
public class CaseHandoverRequest implements TenantAware {

  public enum Status {
    PENDING,
    PENDING_CLIENT_CONSENT,
    GRANTED,
    DENIED,
    CLIENT_CONSENT_DECLINED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @ManyToOne
  @JoinColumn(name = "session_id", nullable = false)
  private Session session;

  @ManyToOne
  @JoinColumn(name = "requester_consultant_id", nullable = false)
  private Consultant requesterConsultant;

  @ManyToOne
  @JoinColumn(name = "previous_consultant_id")
  private Consultant previousConsultant;

  @Column(name = "reason_code", nullable = false, length = 100)
  private String reasonCode;

  @Column(name = "reason_label", nullable = false, length = 255)
  private String reasonLabel;

  @Column(name = "explanation", nullable = false, columnDefinition = "text")
  private String explanation;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 40)
  private Status status;

  @Column(name = "client_consent_required", nullable = false)
  private Boolean clientConsentRequired;

  @Column(name = "policy_authority", nullable = false, length = 255)
  private String policyAuthority;

  @Column(name = "audit_outcome", nullable = false, length = 100)
  private String auditOutcome;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "resolved_at")
  private LocalDateTime resolvedAt;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CaseHandoverRequest)) {
      return false;
    }
    CaseHandoverRequest that = (CaseHandoverRequest) o;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
