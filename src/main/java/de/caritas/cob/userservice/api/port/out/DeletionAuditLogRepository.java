package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DeletionAuditLog;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeletionAuditLogRepository extends JpaRepository<DeletionAuditLog, Long> {

  boolean existsByTargetTypeAndTargetIdAndOutcome(
      DeletionAuditTargetType targetType, String targetId, DeletionAuditOutcome outcome);

  /**
   * Träger the deletion log reports as deleted that still hold an open invite or an active invite
   * link: a revocation at report time that failed (ORISO-Admin#1522, review of #1675).
   */
  @org.springframework.data.jpa.repository.Query(
      "SELECT DISTINCT l.targetId FROM DeletionAuditLog l"
          + " WHERE l.targetType = :type AND l.outcome IN :deleted"
          + " AND (EXISTS (SELECT 1 FROM AccountInvite i"
          + "      WHERE CAST(i.tenantId AS String) = l.targetId AND i.status IN :open)"
          + "  OR EXISTS (SELECT 1 FROM AgencyInviteLink k"
          + "      WHERE CAST(k.tenantId AS String) = l.targetId AND k.status = :active))")
  java.util.List<String> findDeletedTenantsStillInviting(
      @org.springframework.data.repository.query.Param("type") DeletionAuditTargetType type,
      @org.springframework.data.repository.query.Param("deleted")
          java.util.Collection<DeletionAuditOutcome> deleted,
      @org.springframework.data.repository.query.Param("open")
          java.util.Collection<
                  de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus>
              open,
      @org.springframework.data.repository.query.Param("active") String active);

  /** As {@link #findDeletedTenantsStillInviting}, for Beratungsstellen. */
  @org.springframework.data.jpa.repository.Query(
      "SELECT DISTINCT l.targetId FROM DeletionAuditLog l"
          + " WHERE l.targetType = :type AND l.outcome IN :deleted"
          + " AND (EXISTS (SELECT 1 FROM AccountInvite i"
          + "      WHERE CAST(i.agencyId AS String) = l.targetId AND i.status IN :open)"
          + "  OR EXISTS (SELECT 1 FROM AgencyInviteLink k"
          + "      WHERE CAST(k.agencyId AS String) = l.targetId AND k.status = :active))")
  java.util.List<String> findDeletedAgenciesStillInviting(
      @org.springframework.data.repository.query.Param("type") DeletionAuditTargetType type,
      @org.springframework.data.repository.query.Param("deleted")
          java.util.Collection<DeletionAuditOutcome> deleted,
      @org.springframework.data.repository.query.Param("open")
          java.util.Collection<
                  de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus>
              open,
      @org.springframework.data.repository.query.Param("active") String active);
}
