package de.caritas.cob.userservice.api.exception;

/**
 * Signals that an email could not be handed over to the SMTP server (or that the global SMTP
 * configuration required to do so is unavailable). Mirrors the strict contract established in
 * ConsultingTypeService (TEN-INV-U5): callers must never report success — and never persist a SENT
 * state — when this is thrown. Mapped to 502 Bad Gateway.
 */
public class SmtpSendException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SmtpSendException(String message) {
    super(message);
  }

  public SmtpSendException(String message, Throwable cause) {
    super(message, cause);
  }
}
