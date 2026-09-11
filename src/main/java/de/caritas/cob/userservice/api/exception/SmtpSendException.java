package de.caritas.cob.userservice.api.exception;

/**
 * Signals that an email could not be handed over to the SMTP server (or that the global SMTP
 * configuration required to do so is unavailable). Mirrors the strict contract established in
 * ConsultingTypeService (TEN-INV-U5): callers must never report success — and never persist a SENT
 * state — when this is thrown. Mapped to 502 Bad Gateway.
 *
 * <p>#1006: each instance carries a coarse {@link Category}. Only the category reaches the API
 * response body (information-poor per the repository error contract); the detailed message stays in
 * the server log.
 */
public class SmtpSendException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Coarse, client-safe failure category — the only diagnostic detail the 502 body carries. */
  public enum Category {
    /** The settings source (ConsultingTypeService) is unreachable, erroring, or empty. */
    SMTP_SETTINGS_UNAVAILABLE,
    /** SMTP is switched off or the connection configuration is incomplete/invalid. */
    SMTP_DISABLED_OR_INCOMPLETE,
    /** No usable SMTP credentials could be resolved. */
    SMTP_CREDENTIALS_MISSING,
    /** The SMTP server rejected or never confirmed the handover. */
    SMTP_TRANSPORT_FAILED
  }

  private final Category category;

  public SmtpSendException(String message) {
    this(Category.SMTP_TRANSPORT_FAILED, message);
  }

  public SmtpSendException(String message, Throwable cause) {
    this(Category.SMTP_TRANSPORT_FAILED, message, cause);
  }

  public SmtpSendException(Category category, String message) {
    super(message);
    this.category = category;
  }

  public SmtpSendException(Category category, String message, Throwable cause) {
    super(message, cause);
    this.category = category;
  }

  public Category getCategory() {
    return category;
  }
}
