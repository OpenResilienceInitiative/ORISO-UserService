package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.DeletionAuditLog;
import de.caritas.cob.userservice.api.port.out.DeletionAuditLogRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the deletion log read by Admin &gt; Logs. Records ids, role/type labels and the outcome
 * only. The write is best effort and runs in its own transaction: a failing audit insert is logged
 * and swallowed, so it can neither block a deletion nor mark the caller's transaction
 * rollback-only. Inside a caller's transaction the entry is written only after that transaction
 * commits, so a rolled-back deletion never leaves a success entry.
 */
@Service
@Slf4j
public class DeletionAuditService {

  static final String SYSTEM = "SYSTEM";

  private final @NonNull DeletionAuditLogRepository deletionAuditLogRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final TransactionTemplate ownTransaction;

  public DeletionAuditService(
      @NonNull DeletionAuditLogRepository deletionAuditLogRepository,
      @NonNull AuthenticatedUser authenticatedUser,
      @NonNull PlatformTransactionManager transactionManager) {
    this.deletionAuditLogRepository = deletionAuditLogRepository;
    this.authenticatedUser = authenticatedUser;
    this.ownTransaction = new TransactionTemplate(transactionManager);
    this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Records a deletion done by the authenticated caller of the current request. */
  public void recordAsCurrentActor(
      DeletionAuditTargetType targetType,
      String targetId,
      Long tenantId,
      DeletionAuditOutcome outcome) {
    write(
        targetType, targetId, tenantId, outcome, authenticatedUser.getUserId(), resolveActorRole());
  }

  /** Records a deletion done by a scheduler, outside any request. */
  public void recordAsSystem(
      DeletionAuditTargetType targetType,
      String targetId,
      Long tenantId,
      DeletionAuditOutcome outcome) {
    write(targetType, targetId, tenantId, outcome, null, SYSTEM);
  }

  private void write(
      DeletionAuditTargetType targetType,
      String targetId,
      Long tenantId,
      DeletionAuditOutcome outcome,
      String actorId,
      String actorRole) {
    var entry =
        DeletionAuditLog.builder()
            .actorId(actorId)
            .actorRole(actorRole)
            .targetType(targetType)
            .targetId(targetId)
            .tenantId(tenantId)
            .outcome(outcome)
            .createDate(LocalDateTime.now(ZoneOffset.UTC))
            .build();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              persist(entry);
            }
          });
    } else {
      persist(entry);
    }
  }

  private void persist(DeletionAuditLog entry) {
    try {
      ownTransaction.executeWithoutResult(status -> deletionAuditLogRepository.save(entry));
    } catch (RuntimeException e) {
      log.error(
          "Could not write deletion log entry targetType={} targetId={} outcome={}",
          entry.getTargetType(),
          entry.getTargetId(),
          entry.getOutcome(),
          e);
    }
  }

  /** The most privileged role the caller holds, as a stable label. */
  private String resolveActorRole() {
    if (authenticatedUser.isPlatformAdmin()) {
      return "PLATFORM_ADMIN";
    }
    if (authenticatedUser.hasTenantLevelAdminRole()) {
      return "TENANT_ADMIN";
    }
    if (authenticatedUser.isRestrictedAgencyAdmin() || authenticatedUser.isAgencySuperAdmin()) {
      return "AGENCY_ADMIN";
    }
    if (authenticatedUser.isConsultant()) {
      return "CONSULTANT";
    }
    var roles = authenticatedUser.getRoles();
    if (roles != null && roles.contains(UserRole.USER_ADMIN.getValue())) {
      return "USER_ADMIN";
    }
    if (roles != null && roles.contains(UserRole.TECHNICAL.getValue())) {
      return "TECHNICAL";
    }
    return "UNKNOWN";
  }
}
