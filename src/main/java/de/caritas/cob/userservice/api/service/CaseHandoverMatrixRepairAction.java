package de.caritas.cob.userservice.api.service;

/** Idempotent Matrix membership repair actions created by failed handover compensation. */
public enum CaseHandoverMatrixRepairAction {
  JOIN,
  REMOVE,
  POWER_RESTORE,
  /**
   * A counsellor who left a Träger leaves the chat rooms of its cases with their own token (#1419).
   * Unlike {@link #REMOVE} this ignores earlier handover grants: the move ends them all.
   */
  LEAVE,
  /** Retry the owner power raise of a takeover whose new owner could not post without it. */
  POWER_RAISE,
  /**
   * An active supervisor joins the client room read-only. Queued when Synapse rate-limited that
   * invite while the supervisor was added (ORISO-Frontend#2386), so the add does not fail.
   */
  OBSERVE
}
