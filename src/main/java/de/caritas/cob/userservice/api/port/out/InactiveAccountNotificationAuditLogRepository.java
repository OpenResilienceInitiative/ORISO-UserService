package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.InactiveAccountNotificationAuditLog;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InactiveAccountNotificationAuditLogRepository
    extends JpaRepository<InactiveAccountNotificationAuditLog, Long> {

  Optional<InactiveAccountNotificationAuditLog> findByNotificationFingerprint(
      String notificationFingerprint);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT auditLog FROM InactiveAccountNotificationAuditLog auditLog WHERE auditLog.id = :auditLogId")
  Optional<InactiveAccountNotificationAuditLog> findByIdForUpdate(
      @Param("auditLogId") Long auditLogId);
}
