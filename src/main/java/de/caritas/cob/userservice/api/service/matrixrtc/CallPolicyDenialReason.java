package de.caritas.cob.userservice.api.service.matrixrtc;

/**
 * Reason a {@link MatrixRtcCallPolicyService} denied a call-policy request.
 *
 * <p>These codes are safe to log: unlike the raw Matrix room id / user id involved in a denied
 * request, a reason code alone does not expose conversation or user identifiers to log consumers.
 */
public enum CallPolicyDenialReason {
  NOT_ROOM_MEMBER,
  ROOM_MEMBERS_UNAVAILABLE,
  NO_TENANT_CONTEXT,
  TENANT_SETTINGS_UNAVAILABLE,
  CALLS_DISABLED_FOR_TENANT
}
