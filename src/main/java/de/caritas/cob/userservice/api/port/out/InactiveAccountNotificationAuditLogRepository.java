package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.InactiveAccountNotificationAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InactiveAccountNotificationAuditLogRepository
    extends JpaRepository<InactiveAccountNotificationAuditLog, Long> {

  boolean existsByNotificationFingerprint(String notificationFingerprint);
}
