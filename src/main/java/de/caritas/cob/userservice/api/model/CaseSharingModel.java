package de.caritas.cob.userservice.api.model;

/**
 * FE#2494 *4: the case sharing model a case was created under. It is stamped on the case at
 * creation and never follows a later change of the centre's setting: a switch affects new cases
 * only, in both directions (Frank, 2026-11-25).
 */
public enum CaseSharingModel {
  CASE_HANDOVER,
  TEAM_COUNSELLING,
  NONE
}
