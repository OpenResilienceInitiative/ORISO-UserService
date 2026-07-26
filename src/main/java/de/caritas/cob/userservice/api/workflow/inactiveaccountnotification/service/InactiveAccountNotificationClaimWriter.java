package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import de.caritas.cob.userservice.api.model.InactiveAccountNotificationAuditLog;
import de.caritas.cob.userservice.api.port.out.InactiveAccountNotificationAuditLogRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Claims one notification fingerprint before any external email side effect is attempted. */
@Service
@RequiredArgsConstructor
public class InactiveAccountNotificationClaimWriter {

  private final @NonNull InactiveAccountNotificationAuditLogRepository auditLogRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public InactiveAccountNotificationAuditLog claim(InactiveAccountNotificationAuditLog auditLog) {
    return auditLogRepository.saveAndFlush(auditLog);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markEmailDispatched(Long auditLogId) {
    auditLogRepository
        .findById(auditLogId)
        .ifPresent(
            auditLog -> {
              auditLog.setEmailDispatched(true);
              auditLogRepository.saveAndFlush(auditLog);
            });
  }
}
