package de.caritas.cob.userservice.api.admin.service;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityReactivator;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists and fences an identity reactivation saga around its external Keycloak mutation. */
@Service
@RequiredArgsConstructor
public class IdentityReactivationSagaStore {

  private final @NonNull UserRepository userRepository;
  private final @NonNull UsernameTranscoder usernameTranscoder;
  private final @NonNull IdentityReactivator identityReactivator;
  private final @NonNull DeletionLifecycleService deletionLifecycleService;

  /** Commits a unique operation generation before any external identity mutation can start. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IdentityReactivationOperation begin(
      String requestedUsername, String requestedEmail, Long tenantId) {
    String username = normalize(requestedUsername);
    String email = normalize(requestedEmail);
    List<User> candidates =
        userRepository.findAllByUsernameInOrderByCreateDateAsc(
            List.of(
                usernameTranscoder.encodeUsername(username),
                usernameTranscoder.decodeUsername(username)));
    if (candidates.isEmpty()) {
      throw new NotFoundException("No soft-deleted asker matches the supplied identity");
    }
    List<User> exactMatches =
        candidates.stream()
            .filter(
                user ->
                    username.equals(
                        normalize(usernameTranscoder.decodeUsername(user.getUsername()))))
            .filter(user -> email.equals(normalize(user.getEmail())))
            .filter(user -> tenantId.equals(user.getTenantId()))
            .toList();
    if (exactMatches.size() != 1) {
      throw new ConflictException("Asker identity is ambiguous or does not match exactly");
    }

    User user = exactMatches.getFirst();
    assertReactivationEligible(user);
    String operationId = UUID.randomUUID().toString();
    user.setDeletionLifecycleState(DeletionLifecycleState.REACTIVATION_IN_PROGRESS);
    user.setReactivationOperationId(operationId);
    user.setReactivationOperationStartedAt(LocalDateTime.now(ZoneOffset.UTC));
    userRepository.save(user);
    return new IdentityReactivationOperation(
        user.getUserId(), operationId, username, email, tenantId);
  }

  /**
   * Holds the user-row fence across Keycloak and the final MariaDB commit. A pod crash therefore
   * releases this transaction while leaving the independently committed operation claim intact.
   */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      noRollbackFor = IdentityReactivationUnmutatedException.class)
  public void reactivateAndCommit(IdentityReactivationOperation operation, String password) {
    final User user;
    try {
      user = currentOperationForUpdate(operation);
      identityReactivator.reactivateUser(
          operation.userId(),
          operation.username(),
          operation.email(),
          operation.tenantId(),
          password);
    } catch (NotFoundException
        | ConflictException
        | CustomValidationHttpStatusException unmutatedFailure) {
      try {
        releaseCurrentClaimIfPresent(operation);
      } catch (RuntimeException cleanupFailure) {
        unmutatedFailure.addSuppressed(cleanupFailure);
      }
      throw new IdentityReactivationUnmutatedException(unmutatedFailure);
    }
    deletionLifecycleService.cancelUserDeletion(user);
    clearOperation(user);
    userRepository.save(user);
  }

  /** Releases a claim after a proven pre-mutation validation failure. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean abortUnmutated(IdentityReactivationOperation operation) {
    return releaseCurrentClaimIfPresent(operation);
  }

  private boolean releaseCurrentClaimIfPresent(IdentityReactivationOperation operation) {
    return userRepository
        .findByUserIdForUpdate(operation.userId())
        .filter(user -> isCurrentOperation(user, operation.operationId()))
        .map(
            user -> {
              user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
              clearOperation(user);
              userRepository.save(user);
              return true;
            })
        .orElse(false);
  }

  private User currentOperationForUpdate(IdentityReactivationOperation operation) {
    return userRepository
        .findByUserIdForUpdate(operation.userId())
        .filter(user -> isCurrentOperation(user, operation.operationId()))
        .filter(
            user ->
                user.getDeletionLifecycleState() == DeletionLifecycleState.REACTIVATION_IN_PROGRESS)
        .orElseThrow(
            () -> new ConflictException("Asker reactivation operation is no longer current"));
  }

  private void assertReactivationEligible(User user) {
    DeletionLifecycleState state = user.getDeletionLifecycleState();
    if (state == DeletionLifecycleState.HARD_DELETED) {
      throw new NotFoundException("Hard-deleted asker identities cannot be reactivated");
    }
    if (state == DeletionLifecycleState.HARD_DELETE_IN_PROGRESS) {
      throw new ConflictException("Asker hard deletion is already in progress");
    }
    if (state == DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE) {
      throw new ConflictException("Asker hard deletion has already completed destructive steps");
    }
    if (state == DeletionLifecycleState.REACTIVATION_IN_PROGRESS
        || state == DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED
        || user.getReactivationOperationId() != null) {
      throw new ConflictException(
          "Asker identity reactivation or rollback repair is still pending");
    }
    if (user.getDeleteDate() == null) {
      throw new ConflictException("Asker identity is active and cannot be reactivated");
    }
  }

  static boolean isCurrentOperation(User user, String operationId) {
    return operationId != null && Objects.equals(operationId, user.getReactivationOperationId());
  }

  static void clearOperation(User user) {
    user.setReactivationOperationId(null);
    user.setReactivationOperationStartedAt(null);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
