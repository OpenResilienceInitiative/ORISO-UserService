package de.caritas.cob.userservice.api.service.agencyinvitelink;

/** Controls how much identifying information the end-user must provide when redeeming a link. */
public enum InviteLinkAnonymity {
  /** No personal details required; anonymous Keycloak and Matrix accounts are created. */
  FULL
}
