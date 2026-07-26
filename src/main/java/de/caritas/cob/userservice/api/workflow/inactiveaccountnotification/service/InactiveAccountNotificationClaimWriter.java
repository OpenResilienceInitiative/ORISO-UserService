package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import de.caritas.cob.userservice.api.model.InactiveAccountNotificationAuditLog;
import de.caritas.cob.userservice.api.port.out.InactiveAccountNotificationAuditLogRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
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

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<InactiveAccountNotificationAuditLog> findByFingerprint(String fingerprint) {
    return auditLogRepository.findByNotificationFingerprint(fingerprint);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Integer> tryStartEmailDispatch(
      Long auditLogId, LocalDateTime now, Duration recoveryAfter) {
    if (recoveryAfter == null || recoveryAfter.isNegative()) {
      throw new IllegalArgumentException("recoveryAfter must not be negative");
    }
    var auditLog = auditLogRepository.findByIdForUpdate(auditLogId);
    if (auditLog.isEmpty() || auditLog.get().isEmailDispatched()) {
      return Optional.empty();
    }
    var claimedAuditLog = auditLog.get();
    var startedAt = claimedAuditLog.getEmailDispatchStartedAt();
    if (startedAt != null && startedAt.plus(recoveryAfter).isAfter(now)) {
      return Optional.empty();
    }
    claimedAuditLog.setEmailDispatchStartedAt(now);
    claimedAuditLog.setEmailDispatchAttemptCount(
        claimedAuditLog.getEmailDispatchAttemptCount() + 1);
    auditLogRepository.saveAndFlush(claimedAuditLog);
    return Optional.of(claimedAuditLog.getEmailDispatchAttemptCount());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markEmailDispatched(Long auditLogId) {
    auditLogRepository
        .findByIdForUpdate(auditLogId)
        .ifPresent(
            auditLog -> {
              auditLog.setEmailDispatched(true);
              auditLogRepository.saveAndFlush(auditLog);
            });
  }
}
