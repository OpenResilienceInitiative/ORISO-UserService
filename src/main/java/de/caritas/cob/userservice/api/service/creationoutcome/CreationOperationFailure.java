package de.caritas.cob.userservice.api.service.creationoutcome;

import de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.Reason;
import java.util.Objects;
import java.util.UUID;

/** A fixed safe failure, without cause, provider payload or credentials. */
public final class CreationOperationFailure extends RuntimeException {
  private final UUID operationId;
  private final int status;
  private final Reason reason;

  public CreationOperationFailure(UUID operationId, int status, Reason reason) {
    super("Creation operation failed", null, false, false);
    this.operationId = Objects.requireNonNull(operationId);
    if (status != 400
        && status != 404
        && status != 409
        && status != 424
        && status != 500
        && status != 503) throw new IllegalArgumentException("Invalid status");
    this.status = status;
    this.reason = Objects.requireNonNull(reason);
  }

  public UUID operationId() {
    return operationId;
  }

  public int status() {
    return status;
  }

  public Reason reason() {
    return reason;
  }
}
