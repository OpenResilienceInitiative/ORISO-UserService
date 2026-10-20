package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Audit entry for moving a counsellor to another Träger (#1419). It names accounts and counts only:
 * never a name, a case, an advice seeker or a message. Not tenant-filtered on purpose, since only a
 * platform admin acts here and the entry spans two Träger.
 */
@Entity
@Table(name = "consultant_tenant_change_audit")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsultantTenantChangeAudit {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "consultant_id", nullable = false, length = 36)
  private String consultantId;

  @Column(name = "actor_id", nullable = false, length = 36)
  private String actorId;

  @Column(name = "previous_tenant_id")
  private Long previousTenantId;

  @Column(name = "new_tenant_id", nullable = false)
  private Long newTenantId;

  /** Comma-separated agency ids, ascending. */
  @Column(name = "previous_agency_ids", length = 1024)
  private String previousAgencyIds;

  @Column(name = "new_agency_ids", length = 1024)
  private String newAgencyIds;

  @Column(name = "cases_left_with_previous_tenant", nullable = false)
  private int casesLeftWithPreviousTenant;

  @Column(name = "matrix_access_revocations_queued", nullable = false)
  private int matrixAccessRevocationsQueued;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime(6)")
  private LocalDateTime createDate;
}
