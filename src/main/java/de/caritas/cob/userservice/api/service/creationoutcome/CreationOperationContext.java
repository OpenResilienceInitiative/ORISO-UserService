package de.caritas.cob.userservice.api.service.creationoutcome;

import de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.*;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One explicitly passed invocation, never a request-global identity. */
public final class CreationOperationContext {
  private final Candidate candidate;
  private Receipt receipt;
  private boolean dispatchConsumed;

  public CreationOperationContext(Candidate candidate) {
    this.candidate = Objects.requireNonNull(candidate);
  }

  public Candidate candidate() {
    return candidate;
  }

  public synchronized void bind(Receipt persisted) {
    Objects.requireNonNull(persisted);
    if (receipt != null
        && (!receipt.operationId().equals(persisted.operationId())
            || receipt.version() > persisted.version()))
      throw new IllegalStateException("Invalid persisted binding");
    receipt = persisted;
  }

  public synchronized Optional<UUID> persistedOperationId() {
    return Optional.ofNullable(receipt).map(Receipt::operationId);
  }

  public synchronized Receipt receipt() {
    return Objects.requireNonNull(receipt, "No persisted operation");
  }

  public synchronized void consumeDispatch(Claim claim) {
    if (dispatchConsumed
        || !claim.newlyInserted()
        || !candidate.operationId().equals(claim.receipt().operationId())
        || !receipt().equals(claim.receipt()))
      throw new IllegalStateException("No dispatch permit");
    dispatchConsumed = true;
  }
}
