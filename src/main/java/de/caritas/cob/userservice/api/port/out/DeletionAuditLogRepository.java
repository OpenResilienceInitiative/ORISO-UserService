package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DeletionAuditLog;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeletionAuditLogRepository extends JpaRepository<DeletionAuditLog, Long> {

  boolean existsByTargetTypeAndTargetIdAndOutcome(
      DeletionAuditTargetType targetType, String targetId, DeletionAuditOutcome outcome);
}
