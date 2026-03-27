package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.model.InactiveAccountRole;
import java.time.LocalDateTime;
import java.util.Objects;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
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

/** Auditable record for Security-06 inactivity alerts. */
@Entity
@Table(name = "inactive_account_notification_audit_log")
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
public class InactiveAccountNotificationAuditLog implements TenantAware {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "notification_fingerprint", nullable = false, length = 255, unique = true)
  private String notificationFingerprint;

  @Enumerated(EnumType.STRING)
  @Column(name = "account_role", nullable = false, length = 32)
  private InactiveAccountRole accountRole;

  @Column(name = "account_id", nullable = false, length = 64)
  private String accountId;

  @Column(name = "account_tenant_id")
  private Long accountTenantId;

  @Column(name = "last_activity_at", columnDefinition = "datetime")
  private LocalDateTime lastActivityAt;

  @Column(name = "threshold_days", nullable = false)
  private Integer thresholdDays;

  @Column(name = "recipient_admin_id", nullable = false, length = 64)
  private String recipientAdminId;

  @Column(name = "recipient_email", nullable = false, length = 255)
  private String recipientEmail;

  @Column(name = "email_dispatched", nullable = false, columnDefinition = "tinyint")
  private boolean emailDispatched;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime")
  private LocalDateTime createDate;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof InactiveAccountNotificationAuditLog)) {
      return false;
    }
    InactiveAccountNotificationAuditLog that = (InactiveAccountNotificationAuditLog) o;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
