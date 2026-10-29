package de.caritas.cob.userservice.api.service.creationoutcome;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable observations; no dimension certifies login readiness or native absence. */
public final class CreationOperation {
  private CreationOperation() {}

  public enum DispatchState {
    PREPARED,
    DISPATCH_UNKNOWN,
    NATIVE_ID_CAPTURED
  }

  public enum WorkflowState {
    NOT_STARTED,
    IN_PROGRESS,
    RETURNED,
    FAILED,
    UNKNOWN
  }

  public enum RowState {
    NOT_ATTEMPTED,
    COMMIT_UNKNOWN,
    COMMITTED,
    ROLLED_BACK
  }

  public enum OnboardingState {
    NOT_ATTEMPTED,
    OUTCOME_UNKNOWN,
    ISSUER_RETURNED,
    FAILED
  }

  public enum CompensationState {
    NOT_STARTED,
    STARTED_OUTCOME_UNKNOWN,
    RETURNED_UNVERIFIED
  }

  public enum Reason {
    NONE,
    NATIVE_OUTCOME_UNKNOWN,
    NATIVE_CAPTURE_FAILED,
    MANDATORY_ATTRIBUTES_FAILED,
    WORKFLOW_FAILED,
    ROW_COMMIT_FAILED,
    ONBOARDING_FAILED,
    JOURNAL_UNAVAILABLE
  }

  public record Candidate(UUID operationId, UUID operationKey, String ownerId) {
    public Candidate {
      Objects.requireNonNull(operationId);
      Objects.requireNonNull(operationKey);
      if (ownerId == null || ownerId.isBlank() || ownerId.length() > 255)
        throw new IllegalArgumentException("Invalid owner");
    }
  }

  public record Scope(long tenantId, List<Long> agencyIds, String role) {
    public Scope {
      if (!"COUNSELLOR".equals(role)) throw new IllegalArgumentException("Invalid role");
      agencyIds = canonicalIds(agencyIds);
    }
  }

  static List<Long> canonicalIds(List<Long> ids) {
    if (ids == null) return List.of();
    if (ids.stream().anyMatch(id -> id == null || id <= 0))
      throw new IllegalArgumentException("Invalid IDs");
    var canonical = ids.stream().distinct().sorted().toList();
    if (canonical.size() > 100) throw new IllegalArgumentException("Too many IDs");
    return canonical;
  }

  public record Prepared(Candidate candidate, Scope scope, String fingerprint) {
    public Prepared {
      Objects.requireNonNull(candidate);
      Objects.requireNonNull(scope);
      if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid fingerprint");
    }

    @Override
    public String toString() {
      return "Prepared[redacted]";
    }
  }

  public record Receipt(
      UUID operationId,
      UUID operationKey,
      String ownerId,
      Scope scope,
      short fingerprintVersion,
      String requestFingerprint,
      long version,
      Instant createdAt,
      Instant updatedAt,
      Instant dispatchedAt,
      Instant capturedAt,
      String nativeId,
      DispatchState dispatch,
      WorkflowState workflow,
      RowState row,
      OnboardingState onboarding,
      CompensationState compensation,
      Reason reason) {
    public Receipt {
      Objects.requireNonNull(operationId);
      Objects.requireNonNull(operationKey);
      Objects.requireNonNull(ownerId);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(requestFingerprint);
      Objects.requireNonNull(createdAt);
      Objects.requireNonNull(updatedAt);
      Objects.requireNonNull(dispatch);
      Objects.requireNonNull(workflow);
      Objects.requireNonNull(row);
      Objects.requireNonNull(onboarding);
      Objects.requireNonNull(compensation);
      Objects.requireNonNull(reason);
      if (fingerprintVersion != 1 || version < 0)
        throw new IllegalArgumentException("Invalid receipt version");
    }

    @Override
    public String toString() {
      return "Receipt[operationId=" + operationId + ", version=" + version + "]";
    }
  }

  public record Claim(Receipt receipt, boolean newlyInserted) {
    public Claim {
      Objects.requireNonNull(receipt);
    }
  }
}
