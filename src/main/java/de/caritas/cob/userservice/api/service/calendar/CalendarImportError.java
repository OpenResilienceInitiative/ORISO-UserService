package de.caritas.cob.userservice.api.service.calendar;

/** Why a calendar import failed, as the API reports it ({@code CalendarSyncDTO.importError}). */
public enum CalendarImportError {
  /** Not an https address, or one that carries credentials. */
  INVALID_URL,
  /** The host has no address, does not answer in time, redirects or answers with an error. */
  UNREACHABLE,
  /** More than the size limit. */
  TOO_LARGE,
  /** Not an iCalendar document. */
  NOT_ICS,
  /** The host resolves to an address that is not public. */
  FORBIDDEN_ADDRESS
}
