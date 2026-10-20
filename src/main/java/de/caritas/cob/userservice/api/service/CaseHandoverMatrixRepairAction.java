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
  LEAVE
}
