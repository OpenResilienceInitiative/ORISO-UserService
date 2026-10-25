package de.caritas.cob.userservice.api.admin.facade;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.AskerReactivationRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerResponseDTO;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationRepairService;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationSagaStore;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationUnmutatedException;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Wrapper facade to provide admin operations on asker accounts. */
@Service
@RequiredArgsConstructor
public class AskerUserAdminFacade {

  private final @NonNull IdentityDeactivator identityDeactivator;
  private final @NonNull IdentityReactivationSagaStore identityReactivationSagaStore;
  private final @NonNull IdentityReactivationRepairService identityReactivationRepairService;
  private final @NonNull UserService userService;
  private final @NonNull UsernameTranscoder usernameTranscoder;
  private final @NonNull DeletionLifecycleService deletionLifecycleService;
  private final @NonNull AdminScope adminScope;

  private de.caritas.cob.userservice.api.port.out.IdentityClientConfig identityMode;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setIdentityMode(de.caritas.cob.userservice.api.port.out.IdentityClientConfig mode) {
    this.identityMode = java.util.Objects.requireNonNull(mode);
  }

  private de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance
      preDeletion;

  @org.springframework.beans.factory.annotation.Autowired
  public void setScopedPreDeletion(
      @org.springframework.lang.Nullable
          de.caritas.cob.userservice.api.workflow.delete.service.PublicPreDeletionMaintenance owner) {
    preDeletion = owner;
  }

  /**
   * Marks the asker with the given id for deletion.
   *
   * @param userId the id of the asker
   */
  public void markAskerForDeletion(String userId) {
    if (identityMode != null && identityMode.isFiveActorMode()) {
      if (preDeletion == null || !preDeletion.isFiveActorMode())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      preDeletion.startAdminAsker(
          userId,
          acknowledged ->
              acknowledged.completeUser(
                  user -> {
                    deletionLifecycleService.beginUserDeletion(user, null);
                    userService.saveUser(user);
                  }));
      return;
    }
    adminScope.assertMay(Target.adviceSeeker(userId));
    User user =
        userService
            .getUser(userId)
            .orElseThrow(() -> new NotFoundException("Asker with id %s does not exist", userId));

    if (nonNull(user.getDeleteDate())) {
      throw new ConflictException(
          String.format("Asker with id %s is already marked for deletion", userId));
    }

    this.identityDeactivator.deactivateUser(userId);
    this.deletionLifecycleService.beginUserDeletion(user, null);
    this.userService.saveUser(user);
  }

  public void pauseAskerDeletion(String userId, String reason, Integer months, String pausedBy) {
    adminScope.assertMay(Target.adviceSeeker(userId));
    User user =
        userService
            .getUser(userId)
            .or(() -> userService.findDeletedById(userId))
            .orElseThrow(() -> new NotFoundException("Asker with id %s does not exist", userId));
    if (user.getDeleteDate() == null) {
      throw new ConflictException(
          String.format("Asker with id %s is not marked for deletion", userId));
    }
    deletionLifecycleService.pauseUserDeletion(user, reason, months, pausedBy);
    userService.saveUser(user);
  }

  /**
   * Reactivates one exact soft-deleted asker identity for a privileged operator.
   *
   * <p>Username alone is deliberately insufficient because historic data permits duplicates.
   */
  public void reactivateAsker(AskerReactivationRequestDTO request) {
    if (identityMode != null && identityMode.isFiveActorMode()) {
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    }
    assertCallerMayAccessTenant(request.getTenantId());
    var operation =
        identityReactivationSagaStore.begin(
            request.getUsername(), request.getEmail(), request.getTenantId());
    try {
      identityReactivationSagaStore.reactivateAndCommit(operation, request.getPassword());
    } catch (IdentityReactivationUnmutatedException unmutatedFailure) {
      throw unmutatedFailure.originalFailure();
    } catch (RuntimeException reactivationFailure) {
      identityReactivationRepairService.compensate(operation, reactivationFailure);
      throw reactivationFailure;
    }
  }

  private void assertCallerMayAccessTenant(Long requestedTenantId) {
    Long callerTenantId = TenantContext.getCurrentTenant();
    if (callerTenantId == null
        || (!TenantContext.TECHNICAL_TENANT_ID.equals(callerTenantId)
            && !callerTenantId.equals(requestedTenantId))) {
      throw new AccessDeniedException("Caller is not authorized for the requested tenant");
    }
  }

  public AskerResponseDTO getAsker(String userId) {
    adminScope.assertMay(Target.adviceSeeker(userId));
    User user =
        userService
            .getUser(userId)
            .orElseThrow(() -> new NotFoundException("Asker with id %s does not exist", userId));
    AskerResponseDTO asker = new AskerResponseDTO();
    asker.setId(user.getUserId());
    asker.setUsername(this.usernameTranscoder.decodeUsername(user.getUsername()));
    asker.setEmail(user.getEmail());
    return asker;
  }
}
