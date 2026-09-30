package de.caritas.cob.userservice.api.service.accountinvite;

/** Why a waiting invite cannot move on; derived on read, so it clears on its own. */
public enum InviteQueueProblem {
  /** No pending admin invite for the unit; inviting a new admin for the same ID resolves it. */
  NO_UNIT_ADMIN
}
