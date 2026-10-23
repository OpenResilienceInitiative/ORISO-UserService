package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.ClaimedWork;
import java.util.List;

/** Native inactivity operations accept only the current privately owned persisted work. */
public interface AccountInactivityIdentityTransport {
  record Projection(
      String id,
      Long tenantId,
      String username,
      boolean enabled,
      List<String> effectiveRoles,
      String state) {
    public Projection {
      effectiveRoles = List.copyOf(effectiveRoles);
    }
  }

  record Frozen(String body) {
    @Override
    public String toString() {
      return "Frozen[metadata=redacted]";
    }
  }

  sealed interface Outcome permits Applied, NoEffect {}

  record Applied(String receipt) implements Outcome {
    @Override
    public String toString() {
      return "Applied[receipt=redacted]";
    }
  }

  record NoEffect(int status, String receipt) implements Outcome {
    @Override
    public String toString() {
      return "NoEffect[receipt=redacted]";
    }
  }

  Projection read(ClaimedWork work);

  Frozen freeze(ClaimedWork work, Projection current, boolean restoreEnabled);

  void verifyFrozen(ClaimedWork work, Frozen frozen, boolean restoreEnabled);

  Outcome apply(ClaimedWork work, Frozen frozen);

  String verifyApplied(ClaimedWork work, Frozen frozen, String receipt);

  NoEffect verifyNoEffect(ClaimedWork work, Frozen frozen, String receipt);

  void verifyPriorSuspension(ClaimedWork restore, String frozenBody, String receipt);

  /** Fixed data binding only; a JSON value cannot issue private claimed work. */
  default String bindSuspensionCustody(String exactPrefix) {
    throw new IllegalStateException("Original suspension source custody unavailable");
  }

  /** Strict fixed source and original native proof validation, never authority from JSON. */
  default void verifySuspensionCustody(String exactSealedCustody) {
    throw new IllegalStateException("Original suspension source custody unavailable");
  }

  /** Historical fixed RESTORE proof only; does not issue work or current write authority. */
  default void verifyCompletedInactivityRestoreReceipt(String frozenBody, String receipt) {
    throw new IllegalStateException("Completed inactivity restore proof unavailable");
  }
  /** Same fixed historical proof, additionally bound to the exact saved original RESTORE row. */
  default void verifyCompletedInactivityRestoreReceipt(
      String frozenBody, String receipt, String originalRowJson) {
    throw new IllegalStateException("Completed inactivity restore row proof unavailable");
  }
}
