package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import java.util.List;
import java.util.function.Supplier;

/** Native restriction must be acknowledged before protected reservations can be reused. */
public interface ProtectedInvitationCancellation {
  enum Reason {
    REVOKED,
    SUPERSEDED,
    ISSUANCE_ABORTED
  }

  /**
   * Outside an ambient transaction: durably invalidate and record intent, call the receiver, then
   * durably record its exact terminal/fence acknowledgement. Unknown outcomes must throw.
   */
  void cancel(long inviteId, Reason reason, Runnable invalidateLocalInvite);

  /** Runs a local release/transfer only with the saved exact native ACK in its owning SQL lock. */
  <T> T withAcknowledged(long inviteId, Supplier<T> releaseOrTransfer);

  /** Bounded outside-SQL recovery candidates; completed acknowledgements never fill the page. */
  List<Long> pendingInviteIds(int limit);
}
