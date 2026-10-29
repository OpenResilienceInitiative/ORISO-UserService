package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.DeletionAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeletionAuditLogRepository extends JpaRepository<DeletionAuditLog, Long> {}
