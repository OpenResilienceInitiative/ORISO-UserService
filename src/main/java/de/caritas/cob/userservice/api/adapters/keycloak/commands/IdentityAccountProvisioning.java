package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

/** Complete initial account command and durable receipt-only compensation/commit workflow. */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdentityAccountProvisioning {
  private final IdentityProvisioningCommands commands;
  private final IdentityCreationJournalWriter journal;

  @org.springframework.beans.factory.annotation.Autowired
  private de.caritas.cob.userservice.api.port.out.AccountInviteRepository invites;

  /** Reuses the invitation already held by the creation transaction; never resolves a raw token. */
  @org.springframework.transaction.annotation.Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public Optional<de.caritas.cob.userservice.api.model.AccountInvite> heldCreationInvitation(
      String accountId) {
    var row = journal.ownedAttempt(accountId);
    if (!"INVITATION".equals(row.getOriginKind())) return Optional.empty();
    createdRelationGrant(accountId);
    if (row.getProvenance() == null || !row.getProvenance().matches("invite:[1-9][0-9]*"))
      throw new AccessDeniedException("Creation has no exact held invitation provenance");
    var invite =
        invites
            .findById(Long.valueOf(row.getProvenance().substring(7)))
            .orElseThrow(() -> new AccessDeniedException("Owned invitation no longer exists"));
    if (!Objects.equals(invite.getProvisionedUserId(), accountId)
        || !Objects.equals(invite.getTenantId(), row.getTenantId())
        || invite.getAgencyId() == null
        || !Objects.equals(row.getAuthorizedAgencyIds(), invite.getAgencyId().toString())
        || invite.getStatus()
            != de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus.EMAIL_SENT
        || invite.getProvisioningStatus()
            != de.caritas.cob.userservice.api.service.accountinvite.AccountInviteProvisioningStatus
                .IN_PROGRESS
        || invite.getExpiresAt() == null
        || !invite.getExpiresAt().isAfter(java.time.LocalDateTime.now()))
      throw new AccessDeniedException("Relations no longer match the held invitation target");
    return Optional.of(invite);
  }

  public KeycloakTaskCommands.CreationResult create(
      UUID attemptId, KeycloakTaskCommands.AccountCreation command, IdentityCreationOrigin origin) {
    if (!Objects.equals(command.tenantId(), origin.tenantId())
        || !command.registrationKind().equals(origin.registrationKind())
        || !new HashSet<>(command.roles()).equals(new HashSet<>(origin.roles())))
      throw new AccessDeniedException(
          "Initial account command exceeds its originating authorization");
    origin.assertCreationTarget(command);
    IdentityCreationJournalWriter.CreationExecution execution;
    try {
      execution = journal.begin(attemptId, origin, command.username());
    } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
      throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
          "Account creation is already in progress");
    }
    try {
      var receipt =
          commands.create(
              execution.attemptId(),
              command,
              origin.command("account.create", execution.attemptId()));
      if (receipt == null || !execution.attemptId().equals(receipt.attemptId()))
        throw new IllegalStateException("Identity provider returned a foreign creation attempt");
      journal.created(receipt, origin, execution);
      return new KeycloakTaskCommands.CreationResult(
          receipt.attemptId(),
          receipt.accountId(),
          receipt.creationProof(),
          receipt.status(),
          execution.claim());
    } catch (RuntimeException failure) {
      try {
        journal.createResultUncertain(execution, origin);
      } catch (RuntimeException journalFailure) {
        failure.addSuppressed(journalFailure);
      }
      throw failure;
    }
  }

  /**
   * Exact fresh-account setup read, anchored in the original verified creator and durable receipt.
   */
  public KeycloakTaskCommands.AccountProjection setupProjection(String id) {
    var row = journal.ownedAttempt(id);
    var caller =
        IdentityCreationOrigin.verifiedCaller(
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication());
    var kind = IdentityCreationOrigin.Kind.valueOf(row.getRegistrationKind());
    if (!Set.of("OPEN", "COMMIT_REQUESTED", "COMMITTED").contains(row.getStatus())
        || !"HUMAN_ADMIN".equals(row.getOriginKind())
        || !Set.of(
                "human-admin:" + caller.getToken().getSubject(),
                "human-admin:agencies:" + caller.getToken().getSubject())
            .contains(row.getProvenance())
        || !Set.of(
                IdentityCreationOrigin.Kind.AGENCY_ADMIN,
                IdentityCreationOrigin.Kind.TENANT_ADMIN,
                IdentityCreationOrigin.Kind.CONSULTANT)
            .contains(kind))
      throw new AccessDeniedException("Setup read is outside this creator's owned account");
    IdentityCommandAuthorization.verifiedHumanCreation(caller, kind);
    var origin =
        new IdentityCommandAuthorization(
            "HUMAN_ADMIN",
            "account.read",
            id,
            row.getTenantId() == null ? null : row.getTenantId().toString(),
            List.of(row.getInitialRoles().split(",")));
    return commands.ownedRead(id, origin);
  }

  public void acquireLocalSaga(KeycloakTaskCommands.CreationResult receipt) {
    journal.acquireLocalSaga(receipt);
  }

  public void recover(de.caritas.cob.userservice.api.model.IdentityCreationAttempt row) {
    var origin = IdentityCreationOrigin.pendingRecovery(row);
    var result =
        commands.recover(
            UUID.fromString(row.getId()),
            row.getRegistrationKind(),
            origin.command("account.creation-recover", UUID.fromString(row.getId())));
    journal.recovered(row, result);
  }

  public void commit(KeycloakTaskCommands.CreationResult receipt, IdentityCreationOrigin origin) {
    journal.requestAfterSaga(receipt, origin, "COMMIT_REQUESTED");
    commands.commit(receipt, origin.command("account.commit", receipt.attemptId()));
    journal.finish(receipt, "COMMITTED");
  }

  public void compensate(
      KeycloakTaskCommands.CreationResult receipt, IdentityCreationOrigin origin) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      journal.request(receipt, origin, "COMPENSATION_REQUESTED");
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              finishSafely(
                  () -> journal.requestAfterSaga(receipt, origin, "COMPENSATION_REQUESTED"));
            }
          });
    } else finalizeCompensation(receipt, origin);
  }

  private void finalizeCompensation(
      KeycloakTaskCommands.CreationResult receipt, IdentityCreationOrigin origin) {
    journal.requestAfterSaga(receipt, origin, "COMPENSATION_REQUESTED");
    commands.compensate(receipt, origin.command("account.compensate", receipt.attemptId()));
    journal.finish(receipt, "COMPENSATED");
  }

  public void completeAfterLocalSaga(
      KeycloakTaskCommands.CreationResult receipt, IdentityCreationOrigin origin) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      journal.request(receipt, origin, "COMMIT_REQUESTED");
      commit(receipt, origin);
      return;
    }
    journal.prepareCommitInSaga(receipt, origin);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            commit(receipt, origin);
          }

          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK)
              finishSafely(
                  () -> journal.requestAfterSaga(receipt, origin, "COMPENSATION_REQUESTED"));
          }
        });
  }

  public void completeCreatedAccount(String accountId) {
    var row = journal.ownedAttempt(accountId);
    completeAfterLocalSaga(receipt(row), IdentityCreationOrigin.pendingFinalization(row));
  }

  /** Server-side receipt attests the exact initial roles before local agency relationships. */
  public record CreatedRelationGrant(
      String accountId,
      Long tenantId,
      java.util.List<String> initialRoles,
      java.util.List<Long> agencyIds) {}

  public CreatedRelationGrant createdRelationGrant(String accountId) {
    var row = journal.ownedAttempt(accountId);
    if (!"OPEN".equals(row.getStatus())
        || !java.util.Set.of("INVITATION", "IMPORT").contains(row.getOriginKind())
        || !java.util.Set.of("CONSULTANT", "CONSULTANT_AGENCY_ADMIN")
            .contains(row.getRegistrationKind()))
      throw new org.springframework.security.access.AccessDeniedException(
          "Relations need an open owned counselling creation receipt");
    if (row.getAuthorizedAgencyIds() == null || row.getAuthorizedAgencyIds().isBlank())
      throw new org.springframework.security.access.AccessDeniedException(
          "Creation receipt has no authorized agency relation scope");
    return new CreatedRelationGrant(
        accountId,
        row.getTenantId(),
        java.util.List.of(row.getInitialRoles().split(",")),
        java.util.Arrays.stream(row.getAuthorizedAgencyIds().split(","))
            .map(Long::valueOf)
            .toList());
  }

  /** Capture the known failed outcome before any independently persisted local cleanup. */
  public void prepareLocalRollback(String accountId) {
    var row = journal.ownedAttempt(accountId);
    journal.request(
        receipt(row), IdentityCreationOrigin.pendingFinalization(row), "COMPENSATION_REQUESTED");
  }

  /** Persist compensation intent before local rollback; an external failure remains retryable. */
  public void compensateForLocalRollback(String accountId) {
    var row = journal.ownedAttempt(accountId);
    var receipt = receipt(row);
    var origin = IdentityCreationOrigin.pendingFinalization(row);
    finishSafely(() -> compensate(receipt, origin));
  }

  public void compensateCreatedAccount(String accountId) {
    var row = journal.ownedAttempt(accountId);
    compensate(receipt(row), IdentityCreationOrigin.pendingFinalization(row));
  }

  private static KeycloakTaskCommands.CreationResult receipt(
      de.caritas.cob.userservice.api.model.IdentityCreationAttempt row) {
    return new KeycloakTaskCommands.CreationResult(
        UUID.fromString(row.getId()), row.getAccountId(), row.getCreationProof(), "OPEN");
  }

  private static void finishSafely(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException failure) {
      log.warn(
          "Identity creation finalization remains pending ({})",
          failure.getClass().getSimpleName());
    }
  }
}
