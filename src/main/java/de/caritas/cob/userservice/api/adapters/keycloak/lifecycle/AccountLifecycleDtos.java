package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Wire values independent of native Keycloak representations and historical command providers. */
public final class AccountLifecycleDtos {
  private AccountLifecycleDtos() {}

  public record Origin(
      String kind,
      String workflowId,
      String provenance,
      String humanIssuer,
      String humanSubject,
      String humanAuthority,
      List<String> agencyIds) {
    public Origin {
      if (!Set.of("REGISTRATION", "ANONYMOUS", "INVITATION", "HUMAN_ADMIN", "IMPORT")
          .contains(kind)) throw invalid();
      required(workflowId, 255);
      required(provenance, 512);
      agencyIds = List.copyOf(agencyIds);
      if (agencyIds.size() > 100
          || agencyIds.stream().anyMatch(v -> !positive(v))
          || !agencyIds.equals(agencyIds.stream().distinct().sorted().toList())) throw invalid();
      boolean hasHuman = humanIssuer != null || humanSubject != null || humanAuthority != null;
      if ("HUMAN_ADMIN".equals(kind) || hasHuman) {
        required(humanIssuer, 512);
        required(humanSubject, 255);
        required(humanAuthority, 255);
      }
    }

    @Override
    public String toString() {
      return "Origin[authorization=redacted]";
    }
  }

  public record CreationScope(
      String tenantId, String registrationKind, String initialGroup, Origin origin) {
    public CreationScope {
      if (!Set.of("ASKER", "ANONYMOUS", "CONSULTANT", "AGENCY_ADMIN", "CONSULTANT_AGENCY_ADMIN")
          .contains(registrationKind)) throw invalid();
      if (tenantId != null && !positive(tenantId)) throw invalid();
      if (tenantId == null && !Set.of("ASKER", "ANONYMOUS").contains(registrationKind))
        throw invalid();
      required(initialGroup, 512);
      Objects.requireNonNull(origin);
      if ("REGISTRATION".equals(origin.kind()) && !"ASKER".equals(registrationKind)
          || "ANONYMOUS".equals(origin.kind()) && !"ANONYMOUS".equals(registrationKind)
          || "IMPORT".equals(origin.kind()) && !"CONSULTANT".equals(registrationKind))
        throw invalid();
    }
  }

  public record AccountCreation(
      String username,
      String email,
      String firstName,
      String lastName,
      String preferredLanguage,
      String password,
      boolean passwordTemporary) {
    public AccountCreation {
      required(username, 255);
      optional(email, 255);
      optional(firstName, 255);
      optional(lastName, 255);
      optional(preferredLanguage, 20);
      optional(password, 4096);
    }

    @Override
    public String toString() {
      return "AccountCreation[profile=redacted,password=redacted]";
    }
  }

  public record CreationIdentity(CreationScope scope, String creationFingerprint) {
    public CreationIdentity {
      Objects.requireNonNull(scope);
      digest(creationFingerprint);
    }
  }

  public record ExecutionRef(UUID executionId, long generation) {
    public ExecutionRef {
      Objects.requireNonNull(executionId);
      if (generation < 0) throw invalid();
    }
  }

  public record LocalOutcome(long journalVersion, UUID outboxId, String effectsDigest) {
    public LocalOutcome {
      if (journalVersion < 1) throw invalid();
      Objects.requireNonNull(outboxId);
      digest(effectsDigest);
    }
  }

  public record CreateRequest(
      CreationIdentity identity, ExecutionRef execution, AccountCreation account) {
    public CreateRequest {
      Objects.requireNonNull(identity);
      Objects.requireNonNull(execution);
      Objects.requireNonNull(account);
      if (execution.generation() != 0) throw invalid();
    }

    @Override
    public String toString() {
      return "CreateRequest[creation=redacted]";
    }
  }

  public record OwnedReadRequest(
      CreationIdentity identity, ExecutionRef execution, String accountId, String creationProof) {
    public OwnedReadRequest {
      Objects.requireNonNull(identity);
      Objects.requireNonNull(execution);
      required(accountId, 255);
      required(creationProof, 16384);
    }

    @Override
    public String toString() {
      return "OwnedReadRequest[receipt=redacted]";
    }
  }

  public record RecoverRequest(
      CreationIdentity identity, long expectedGeneration, UUID recoveryId) {
    public RecoverRequest {
      Objects.requireNonNull(identity);
      Objects.requireNonNull(recoveryId);
      if (expectedGeneration < 0) throw invalid();
    }
  }

  public record CommitRequest(
      CreationIdentity identity,
      ExecutionRef execution,
      String accountId,
      String creationProof,
      LocalOutcome localCommit) {
    public CommitRequest {
      Objects.requireNonNull(identity);
      Objects.requireNonNull(execution);
      Objects.requireNonNull(localCommit);
      required(accountId, 255);
      required(creationProof, 16384);
    }

    @Override
    public String toString() {
      return "CommitRequest[receipt=redacted]";
    }
  }

  public record CompensateRequest(
      CreationIdentity identity,
      ExecutionRef execution,
      String accountId,
      String creationProof,
      LocalOutcome localCleanup) {
    public CompensateRequest {
      Objects.requireNonNull(identity);
      Objects.requireNonNull(execution);
      Objects.requireNonNull(localCleanup);
      if (accountId != null || creationProof != null) {
        required(accountId, 255);
        required(creationProof, 16384);
      }
    }

    @Override
    public String toString() {
      return "CompensateRequest[receipt=redacted]";
    }
  }

  public record AttemptResult(
      UUID attemptId,
      String accountId,
      String state,
      ExecutionRef execution,
      String creationProof,
      String recoveryReceipt,
      String terminalReceipt) {
    public AttemptResult {
      Objects.requireNonNull(attemptId);
      Objects.requireNonNull(execution);
      if (!Set.of("OPEN", "RECOVERY_CLAIMED", "COMMITTED", "COMPENSATED", "ABANDONED")
          .contains(state)) throw invalid();
      if ("ABANDONED".equals(state)) {
        if (accountId != null || creationProof != null) throw invalid();
      } else {
        required(accountId, 255);
        required(creationProof, 16384);
      }
      if (Set.of("COMMITTED", "COMPENSATED", "ABANDONED").contains(state))
        required(terminalReceipt, 16384);
      if ("RECOVERY_CLAIMED".equals(state)) required(recoveryReceipt, 16384);
    }

    @Override
    public String toString() {
      return "AttemptResult[state=" + state + ",receipt=redacted]";
    }
  }

  public record AccountProjection(
      String id,
      String username,
      String email,
      String firstName,
      String lastName,
      String tenantId,
      String preferredLanguage,
      boolean enabled,
      boolean emailVerified,
      List<String> effectiveRoles,
      boolean passwordChangeRequired) {
    public AccountProjection {
      effectiveRoles = List.copyOf(effectiveRoles);
    }

    @Override
    public String toString() {
      return "AccountProjection[profile=redacted]";
    }
  }

  private static boolean positive(String v) {
    return v != null && v.matches("[1-9][0-9]{0,18}");
  }

  private static void digest(String v) {
    if (v == null || !v.matches("[0-9a-f]{64}")) throw invalid();
  }

  private static void required(String v, int n) {
    if (v == null || v.isBlank() || v.length() > n) throw invalid();
  }

  private static void optional(String v, int n) {
    if (v != null && v.length() > n) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid bounded account lifecycle value");
  }
}
