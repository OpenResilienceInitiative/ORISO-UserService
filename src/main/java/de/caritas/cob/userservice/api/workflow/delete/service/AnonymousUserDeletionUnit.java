package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Deletes one asker account (anonymous or temporary) in a transaction of its own. */
@Service
@RequiredArgsConstructor
public class AnonymousUserDeletionUnit {

  private final @NonNull UserRepository userRepository;
  private final @NonNull DeleteUserAccountService deleteUserAccountService;
  private final @NonNull AnonymousUserDeletionCandidates anonymousCandidates;
  private final @NonNull DeletionLifecycleService lifecycle;
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

  @org.springframework.beans.factory.annotation.Value("${user.temporary.deleteWorkflow.maxAge}")
  private java.time.Duration temporaryMaxAge;

  /**
   * Deletes the given user and commits when this method returns.
   *
   * <p>{@link Propagation#REQUIRES_NEW} keeps one user's failure to itself. A genuine database
   * error marks its persistence context rollback-only, and a batch-wide transaction would then
   * discard every other user's completed work while their Matrix and Keycloak deletions had already
   * happened — which is what made the scheduler replay those external calls every hour.
   *
   * <p>The user is loaded here rather than handed in, so it is managed by this transaction's own
   * persistence context and the delete does not take Hibernate's merge path. A user that no longer
   * exists is treated as already deleted.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<DeletionWorkflowError> deleteUser(String userId) {
    return userRepository
        .findByIdForDeletionUpdate(userId)
        .map(this::performDeletion)
        .orElseGet(java.util.Collections::emptyList);
  }

  private List<DeletionWorkflowError> performDeletion(User user) {
    boolean temporaryExpired =
        user.isTemporaryAccount()
            && user.getCreateDate() != null
            && user.getCreateDate().isBefore(java.time.LocalDateTime.now().minus(temporaryMaxAge));
    boolean anonymousExpired =
        anonymousCandidates.findOverdueAnonymousUserIds().contains(user.getUserId());
    if (!temporaryExpired && !anonymousExpired) return java.util.List.of();
    if (user.getDeleteDate() != null && !lifecycle.isReadyForHardDelete(user))
      return java.util.List.of();
    if (user.getDeleteDate() == null) {
      lifecycle.beginUserDeletion(
          user, temporaryExpired ? "expired-temporary-account" : "overdue-anonymous-account");
      userRepository.save(user);
      entityManager.flush();
    }
    return deleteUserAccountService.performUserDeletion(user);
  }
}
