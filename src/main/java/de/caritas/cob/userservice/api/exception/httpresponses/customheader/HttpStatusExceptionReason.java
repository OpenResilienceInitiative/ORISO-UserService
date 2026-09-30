package de.caritas.cob.userservice.api.exception.httpresponses.customheader;

public enum HttpStatusExceptionReason {
  USERNAME_NOT_AVAILABLE,
  USERNAME_NOT_VALID,
  EMAIL_NOT_AVAILABLE,
  EMAIL_NOT_VALID,
  MISSING_ABSENCE_MESSAGE_FOR_ABSENT_USER,
  CONSULTANT_AGENCY_RELATION_DOES_NOT_EXIST,
  CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_IS_STILL_ACTIVE,
  CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_HAS_OPEN_ENQUIRIES,
  CONSULTANT_HAS_ACTIVE_OR_ARCHIVE_SESSIONS,
  ADMIN_AGENCY_RELATION_DOES_NOT_EXIST,
  DEMOGRAPHICS_ATTRIBUTE_MISSING,
  USER_ALREADY_REGISTERED_WITH_AGENCY_AND_TOPIC,
  USER_ALREADY_REGISTERED_TO_CONSULTING_TYPE,
  NUMBER_OF_LICENSES_EXCEEDED,
  TENANT_LICENSING_NOT_CONFIGURED,
  PASSWORD_NOT_VALID,
  CONSULTANT_IDENTITY_ALREADY_GRANTED,
  CHAT_RECOVERY_POLICY_UNAVAILABLE,
  ROLE_NOT_FOUND,
  /** The admin already holds the role they tried to assign themselves. */
  SELF_ASSIGNMENT_ALREADY_EXISTS,
  /** An invite into a not-yet-created unit needs a pending admin invite for that unit. */
  NO_PENDING_UNIT_ADMIN,
  /** A waiting invite cannot be sent before its unit exists. */
  UNIT_NOT_CREATED,
  /** The invite's account exists; its roles change in the Users area or by adding a role. */
  INVITE_ALREADY_ACCEPTED,
  /** The invite was revoked, replaced or has expired. */
  INVITE_NOT_PENDING,
  /** Träger-level and agency-level invites differ in placement and link: revoke, invite anew. */
  ROLE_CHANGE_NEEDS_NEW_INVITE,
  /** The change would leave a new unit without any pending admin invite. */
  ONLY_UNIT_ADMIN,
  /** The account already holds that role, or a higher admin role. */
  ROLE_ALREADY_GRANTED,
  /** Another request is changing this invite right now; try again. */
  INVITE_BUSY,
  /** The invite changed while the invitee filled in the wizard; reload the link. */
  INVITE_CHANGED
}
