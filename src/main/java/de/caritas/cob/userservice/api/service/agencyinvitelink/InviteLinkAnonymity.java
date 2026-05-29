package de.caritas.cob.userservice.api.service.agencyinvitelink;

/** Controls how much identifying information the end-user must provide when redeeming a link. */
public enum InviteLinkAnonymity {
  /** No personal details required — a fully anonymous Keycloak + RocketChat account is created. */
  FULL
}
