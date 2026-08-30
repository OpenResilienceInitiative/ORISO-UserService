package de.caritas.cob.userservice.api.admin.facade;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.AskerReactivationRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.IdentityReactivator;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Wrapper facade to provide admin operations on asker accounts. */
@Service
@RequiredArgsConstructor
public class AskerUserAdminFacade {

  private final @NonNull IdentityDeactivator identityDeactivator;
  private final @NonNull IdentityReactivator identityReactivator;
  private final @NonNull UserService userService;
  private final @NonNull UsernameTranscoder usernameTranscoder;
  private final @NonNull DeletionLifecycleService deletionLifecycleService;

  /**
   * Marks the asker with the given id for deletion.
   *
   * @param userId the id of the asker
   */
  public void markAskerForDeletion(String userId) {
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
  @Transactional
  public void reactivateAsker(AskerReactivationRequestDTO request) {
    assertCallerMayAccessTenant(request.getTenantId());
    String username = normalize(request.getUsername());
    String email = normalize(request.getEmail());
    var candidates = userService.findUsersByUsernameIncludingDeleted(username);
    if (candidates.isEmpty()) {
      throw new NotFoundException("No soft-deleted asker matches the supplied identity");
    }

    var exactMatches =
        candidates.stream()
            .filter(
                user ->
                    username.equals(
                        normalize(usernameTranscoder.decodeUsername(user.getUsername()))))
            .filter(user -> email.equals(normalize(user.getEmail())))
            .filter(user -> request.getTenantId().equals(user.getTenantId()))
            .toList();
    if (exactMatches.size() != 1) {
      throw new ConflictException("Asker identity is ambiguous or does not match exactly");
    }

    User user = exactMatches.getFirst();
    if (user.getDeleteDate() == null) {
      throw new ConflictException("Asker identity is active and cannot be reactivated");
    }

    registerIdentityRollbackCompensation(user.getUserId());
    identityReactivator.reactivateUser(
        user.getUserId(), username, email, request.getTenantId(), request.getPassword());
    deletionLifecycleService.cancelUserDeletion(user);
    userService.saveUser(user);
  }

  private void assertCallerMayAccessTenant(Long requestedTenantId) {
    Long callerTenantId = TenantContext.getCurrentTenant();
    if (callerTenantId == null
        || (!TenantContext.TECHNICAL_TENANT_ID.equals(callerTenantId)
            && !callerTenantId.equals(requestedTenantId))) {
      throw new AccessDeniedException("Caller is not authorized for the requested tenant");
    }
  }

  private void registerIdentityRollbackCompensation(String userId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Identity reactivation requires an active transaction");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != TransactionSynchronization.STATUS_COMMITTED) {
              identityDeactivator.deactivateUser(userId);
            }
          }
        });
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
  }

  public AskerResponseDTO getAsker(String userId) {
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
