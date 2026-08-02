package de.caritas.cob.userservice.api.service.handshake;

/**
 * One kind of durable outbox job (ADR-018 §4). Handlers run outside any database transaction and
 * must be idempotent: a job is retried until it succeeds or — where that is allowed — until its
 * attempt limit is reached.
 */
public interface SupportAccessJobHandler {

  String PROVISION_ROOM = "PROVISION_ROOM";
  String REVOKE_ACCESS = "REVOKE_ACCESS";
  String PURGE_CALL_ROOM = "PURGE_CALL_ROOM";

  /** The {@code event_type} this handler claims. */
  String jobType();

  /**
   * @param aggregateId handshake id for provisioning, session id for withdrawal jobs
   */
  void handle(String aggregateId);

  /**
   * Whether the job may ever be given up on. Provisioning is bounded — a session that cannot be
   * built becomes PROVISIONING_FAILED and is visible to operations. Withdrawal is not bounded: as
   * long as a support identity might still reach a room, reporting anything terminal would be a
   * false security claim, so it is retried forever and alerts instead.
   */
  boolean retriesForever();
}
