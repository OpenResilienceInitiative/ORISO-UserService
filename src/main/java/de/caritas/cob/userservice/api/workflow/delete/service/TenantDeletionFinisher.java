package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.tenant.TenantDeletionFinishClient;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * The last step of a Träger deletion (ORISO-Admin#1525, rule 3 of the deletion epic
 * ORISO-Admin#1520: the Träger goes last). Runs after the nightly account deletion. For every
 * Träger TenantService has marked:
 *
 * <ol>
 *   <li>while any counsellor, advice seeker or admin of it is left, marked or not, it waits;
 *   <li>then it marks the Träger's technical group-chat users, which back its group chats and so go
 *       only now, and waits for the account deletion to remove them;
 *   <li>once nothing is left, TenantService removes the Träger and the deletion log records it.
 * </ol>
 *
 * <p>Nothing here deletes accounts itself: the nightly account deletion does, with its safeguards.
 * A Träger whose cascade left something unmarked waits until an admin deletes it again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantDeletionFinisher {

  /** Where a Träger in deletion stands after this run. */
  public enum Step {
    WAITING_FOR_ACCOUNTS,
    MARKED_TECHNICAL_USERS,
    WAITING_FOR_TECHNICAL_USERS,
    REMOVED,
    FAILED
  }

  public record Outcome(long tenantId, Step step) {}

  private final @NonNull TenantDeletionFinishClient tenantService;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull UserRepository userRepository;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull DeletionLifecycleService deletionLifecycleService;
  private final @NonNull DeletionAuditService deletionAuditService;
  private final @NonNull IdentityClientConfig identity;
  private final @NonNull Environment environment;

  /**
   * Switched by the Helm value tenantService.tenantDeletionFinishEnabled, which also makes
   * TenantService require TENANT_DELETION. It can switch the step off, never on without
   * FIVE_ACTORS.
   */
  static final String ENABLED = "deletion.tenant-finish.enabled";

  public List<Outcome> finishMarkedTenants() {
    if (Boolean.FALSE.equals(environment.getProperty(ENABLED, Boolean.class))) {
      log.info("Träger deletions are not finished: {} is false", ENABLED);
      return List.of();
    }
    if (!fiveActorMode()) {
      log.info("Träger deletions are not finished: TASK_IDENTITY_MODEL is not FIVE_ACTORS");
      return List.of();
    }
    List<Long> tenantIds;
    try {
      tenantIds = tenantService.findTenantsMarkedForDeletion();
    } catch (RuntimeException failure) {
      log.warn("Could not list Träger in deletion; retrying next run ({})", failure.getMessage());
      return List.of();
    }
    var outcomes = new ArrayList<Outcome>();
    for (Long tenantId : tenantIds) {
      Step step;
      try {
        step = finish(tenantId);
      } catch (RuntimeException failure) {
        log.warn(
            "Could not finish deleting Träger {}; retrying next run ({})",
            tenantId,
            failure.getMessage());
        step = Step.FAILED;
      }
      outcomes.add(new Outcome(tenantId, step));
    }
    log.info("Träger deletions this run: {}", outcomes);
    return outcomes;
  }

  private Step finish(long tenantId) {
    long accounts =
        consultantRepository.countByTenantId(tenantId)
            + userRepository.countAdviceSeekersOfTenant(tenantId)
            + adminRepository.countByTenantIdAndType(tenantId, AdminType.AGENCY)
            + adminRepository.countByTenantIdAndType(tenantId, AdminType.TENANT);
    if (accounts > 0) {
      return Step.WAITING_FOR_ACCOUNTS;
    }
    List<User> technical = userRepository.findTechnicalUsersOfTenant(tenantId);
    var unmarked = technical.stream().filter(user -> user.getDeleteDate() == null).toList();
    if (!unmarked.isEmpty()) {
      unmarked.forEach(
          user -> {
            deletionLifecycleService.beginUserDeletion(user, null);
            userRepository.save(user);
          });
      return Step.MARKED_TECHNICAL_USERS;
    }
    if (!technical.isEmpty()) {
      return Step.WAITING_FOR_TECHNICAL_USERS;
    }
    tenantService.removeTenant(tenantId);
    deletionAuditService.recordAsSystem(
        DeletionAuditTargetType.TENANT,
        String.valueOf(tenantId),
        tenantId,
        DeletionAuditOutcome.EXECUTED);
    return Step.REMOVED;
  }

  private boolean fiveActorMode() {
    try {
      return identity.isFiveActorMode();
    } catch (RuntimeException unsupportedModel) {
      return false;
    }
  }
}
