package de.caritas.cob.userservice.api.service.handshake;

/**
 * The durable jobs support access runs outside a transaction (ADR-018 §4), each carrying its own
 * give-up policy.
 *
 * <p>The policy lives on the job rather than in the runner because it is a property of what the job
 * means, not of how it is executed. Provisioning may be abandoned: a session that cannot be built
 * becomes PROVISIONING_FAILED and is visible to operations. Withdrawal may not: while it is
 * unproven that a support identity lost its access, giving up would turn an outage into a false
 * security claim, so it is retried forever and alerts instead.
 */
public enum SupportAccessJob {
  PROVISION_ROOM(false),
  REVOKE_ACCESS(true),
  PURGE_CALL_ROOM(true);

  private final boolean retriesForever;

  SupportAccessJob(boolean retriesForever) {
    this.retriesForever = retriesForever;
  }

  public boolean retriesForever() {
    return retriesForever;
  }

  /** Unknown or renamed job types must not be silently skipped. */
  public static SupportAccessJob of(String eventType) {
    try {
      return valueOf(eventType);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalStateException("No handler for job type " + eventType, e);
    }
  }
}
