package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;

import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Authenticates existing ordinary receiver transitions before any durable cleanup decision. */
final class OrdinaryLifecycleReceipts {
  private OrdinaryLifecycleReceipts() {}

  static void verify(
      AccountLifecycleCodec codec,
      String realmName,
      String realmId,
      TaskActorCredentials owner,
      UUID id,
      Object request,
      AttemptResult result) {
    CreationIdentity identity =
        switch (request) {
          case CreateRequest value -> value.identity();
          case RecoverRequest value -> value.identity();
          case CommitRequest value -> value.identity();
          case CompensateRequest value -> value.identity();
          default -> throw invalid();
        };
    require(id.equals(result.attemptId()));
    var proof =
        "ABANDONED".equals(result.state())
            ? null
            : codec.verifyCreationProof(
                result.creationProof(),
                realmName,
                realmId,
                owner.clientId(),
                owner.serviceSubject(),
                id,
                result.accountId(),
                identity);
    if (request instanceof CreateRequest create) {
      require(
          "OPEN".equals(result.state())
              && create.execution().equals(result.execution())
              && result.recoveryReceipt() == null
              && result.terminalReceipt() == null);
      return;
    }
    if (request instanceof CommitRequest commit) {
      require("COMMITTED".equals(result.state()));
      owned(commit.execution(), commit.accountId(), commit.creationProof(), result);
    } else if (request instanceof CompensateRequest compensate) {
      require(Set.of("COMPENSATED", "ABANDONED").contains(result.state()));
      owned(compensate.execution(), compensate.accountId(), compensate.creationProof(), result);
    } else if (request instanceof RecoverRequest recover) {
      require(!"OPEN".equals(result.state()));
      boolean originalFence =
          recover.expectedGeneration() < Long.MAX_VALUE
              && result.execution().generation() == recover.expectedGeneration() + 1
              && recover.recoveryId().equals(result.execution().executionId());
      boolean historicalTerminal =
          Set.of("COMMITTED", "COMPENSATED", "ABANDONED").contains(result.state())
              && result.execution().generation() == recover.expectedGeneration();
      require(originalFence || historicalTerminal);
    }
    String recoveryDigest = null;
    if (result.recoveryReceipt() != null) {
      require(result.execution().generation() > 0 && !"COMMITTED".equals(result.state()));
      // Compensation keeps the original recovery evidence, not a signature of its later state.
      var original =
          new RecoverRequest(
              identity, result.execution().generation() - 1, result.execution().executionId());
      recoveryDigest = codec.digest("payload", original);
      var recovery =
          codec.verifyLifecycleTransitionReceipt(result.recoveryReceipt(), "recovery-receipt");
      bindings(codec, realmId, owner, id, identity, result, proof, recovery);
      require(
          ("ABANDONED".equals(result.state()) ? "ABANDONED" : "RECOVERY_CLAIMED")
                  .equals(recovery.state())
              && "recover".equals(recovery.operation())
              && recoveryDigest.equals(recovery.commandDigest()));
    }
    if ("RECOVERY_CLAIMED".equals(result.state())) {
      require(result.recoveryReceipt() != null && result.terminalReceipt() == null);
      require(
          request instanceof RecoverRequest
              && codec.digest("payload", request).equals(recoveryDigest));
      return;
    }
    var terminal =
        codec.verifyLifecycleTransitionReceipt(result.terminalReceipt(), "terminal-receipt");
    bindings(codec, realmId, owner, id, identity, result, proof, terminal);
    require(result.state().equals(terminal.state()));
    switch (result.state()) {
      case "COMMITTED" -> {
        require(result.recoveryReceipt() == null && "commit".equals(terminal.operation()));
        if (request instanceof CommitRequest)
          require(codec.digest("payload", request).equals(terminal.commandDigest()));
      }
      case "COMPENSATED" -> {
        require("compensate".equals(terminal.operation()));
        if (request instanceof CompensateRequest)
          require(codec.digest("payload", request).equals(terminal.commandDigest()));
      }
      case "ABANDONED" -> {
        require(
            result.recoveryReceipt() != null
                && "abandon".equals(terminal.operation())
                && recoveryDigest.equals(terminal.commandDigest()));
        if (request instanceof RecoverRequest recover
            && result.execution().generation() != recover.expectedGeneration())
          require(codec.digest("payload", request).equals(recoveryDigest));
      }
      default -> throw invalid();
    }
    // A recover reply may carry a previously authenticated terminal command's digest.
    // It is historical reconciliation data; the dispatcher never treats it as a new fence.
  }

  private static void owned(
      ExecutionRef execution, String account, String proof, AttemptResult result) {
    require(
        execution.equals(result.execution())
            && Objects.equals(account, result.accountId())
            && Objects.equals(proof, result.creationProof()));
  }

  private static void bindings(
      AccountLifecycleCodec codec,
      String realm,
      TaskActorCredentials owner,
      UUID id,
      CreationIdentity identity,
      AttemptResult result,
      AccountLifecycleCodec.CreationProofPayload proof,
      AccountLifecycleCodec.LifecycleTransitionReceipt receipt) {
    require(
        realm.equals(receipt.realmId())
            && id.equals(receipt.attemptId())
            && Objects.equals(result.accountId(), receipt.accountId())
            && owner.clientId().equals(receipt.ownerClient())
            && owner.serviceSubject().equals(receipt.ownerSubject())
            && codec.canonical(identity.scope()).equals(receipt.scope())
            && identity.creationFingerprint().equals(receipt.creationFingerprint())
            && result.execution().generation() == receipt.generation()
            && result.execution().executionId().equals(receipt.executionId())
            && receipt.groupId() != null
            && !receipt.groupId().isBlank()
            && receipt.groupId().length() <= 512
            && receipt.roleFingerprint() != null
            && receipt.roleFingerprint().matches("[0-9a-f]{64}")
            && receipt.commandDigest() != null
            && receipt.commandDigest().matches("[0-9a-f]{64}"));
    if (proof != null)
      require(
          proof.initialGroupId().equals(receipt.groupId())
              && proof.initialRoleFingerprint().equals(receipt.roleFingerprint()));
  }

  private static void require(boolean condition) {
    if (!condition) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid ordinary lifecycle transition receipt");
  }
}
