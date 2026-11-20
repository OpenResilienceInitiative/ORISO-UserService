package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Deletion log entry: who deleted what, when, with which outcome. Holds opaque ids and labels only
 * - never names, e-mail addresses or message content of the deleted person.
 */
@Entity
@Table(name = "deletion_audit_log")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class DeletionAuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  /** Null when the system (scheduler) acted. */
  @Column(name = "actor_id", length = 64)
  private String actorId;

  @Column(name = "actor_role", nullable = false, length = 32)
  private String actorRole;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", nullable = false, length = 32)
  private DeletionAuditTargetType targetType;

  @Column(name = "target_id", nullable = false, length = 64)
  private String targetId;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, length = 16)
  private DeletionAuditOutcome outcome;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime")
  private LocalDateTime createDate;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DeletionAuditLog that)) {
      return false;
    }
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
