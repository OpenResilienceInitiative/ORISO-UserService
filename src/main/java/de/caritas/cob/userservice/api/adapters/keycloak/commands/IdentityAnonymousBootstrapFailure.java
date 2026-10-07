package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Guest bootstrap phase is durable; only its own committed user may enter normal deletion. */
@Service
@RequiredArgsConstructor
@Transactional
public class IdentityAnonymousBootstrapFailure {
  private final IdentityCreationJournalWriter journal;
  private final UserRepository users;
  private final SessionRepository sessions;
  private final DeletionLifecycleService lifecycle;

  public void record(String accountId, Long sessionId, RuntimeException caughtBootstrapFailure) {
    if (caughtBootstrapFailure == null) throw denied();
    var attempt = journal.ownedAttempt(accountId);
    var user = require(attempt, sessionId);
    journal.recordAnonymousBootstrapFailure(UUID.fromString(attempt.getId()), sessionId);
    if ("COMMITTED".equals(attempt.getStatus())) markFailed(attempt, user);
  }

  /**
   * This same-row fence executes before any guest access/refresh token is returned to the caller.
   */
  public void complete(String accountId, Long sessionId) {
    var attempt = journal.ownedAttempt(accountId);
    var user = require(attempt, sessionId);
    if (!"COMMITTED".equals(attempt.getStatus())
        || user.getDeleteDate() != null
        || attempt.getBootstrapFailedAt() != null
        || journal.anonymousBootstrapExpired(attempt)) throw denied();
    journal.finishAnonymousBootstrap(UUID.fromString(attempt.getId()), sessionId);
  }

  public void reconcile(UUID attemptId) {
    var attempt = journal.attemptInSaga(attemptId);
    if (!"COMMITTED".equals(attempt.getStatus()) || attempt.getBootstrapSessionId() == null) return;
    if (attempt.getBootstrapFailedAt() == null && !journal.anonymousBootstrapExpired(attempt))
      return;
    var user = require(attempt, attempt.getBootstrapSessionId());
    markFailed(attempt, user);
  }

  private void markFailed(IdentityCreationAttempt attempt, User user) {
    lifecycle.beginUserDeletion(user, "failed-anonymous-bootstrap");
    users.save(
        user); // normal maintenance deletion is authorized only after this real durable marker
    journal.finishAnonymousBootstrap(
        UUID.fromString(attempt.getId()), attempt.getBootstrapSessionId());
  }

  private User require(IdentityCreationAttempt attempt, Long sessionId) {
    if (!Set.of("COMMITTED", "COMMIT_REQUESTED").contains(attempt.getStatus())
        || !"ANONYMOUS".equals(attempt.getRegistrationKind())
        || !"ANONYMOUS".equals(attempt.getOriginKind())
        || sessionId == null
        || !Objects.equals(attempt.getBootstrapSessionId(), sessionId)) throw denied();
    var session =
        sessions
            .findByIdForUpdate(sessionId)
            .orElseThrow(IdentityAnonymousBootstrapFailure::denied);
    var user =
        users
            .findById(attempt.getAccountId())
            .orElseThrow(IdentityAnonymousBootstrapFailure::denied);
    if (session.getRegistrationType() != Session.RegistrationType.ANONYMOUS
        || session.getUser() == null
        || !user.getUserId().equals(session.getUser().getUserId())
        || !Objects.equals(attempt.getTenantId(), user.getTenantId())
        || !Objects.equals(user.getTenantId(), session.getUser().getTenantId())) throw denied();
    return user;
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException(
        "Guest bootstrap is outside its committed owned account/session phase");
  }
}
