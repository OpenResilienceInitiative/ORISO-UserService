package de.caritas.cob.userservice.api.service.calendar;

/** A calendar import that failed for a reason the counsellor is told about. */
public class CalendarImportException extends RuntimeException {

  private final CalendarImportError error;

  public CalendarImportException(CalendarImportError error) {
    // No detail on purpose: the message of an import failure must never carry the address.
    super(error.name());
    this.error = error;
  }

  public CalendarImportError error() {
    return error;
  }
}
