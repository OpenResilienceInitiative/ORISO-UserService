package de.caritas.cob.userservice.api.exception;

/**
 * Signals that an email could not be handed over to the SMTP server (or that the global SMTP
 * configuration required to do so is unavailable). Mirrors the strict contract established in
 * ConsultingTypeService (TEN-INV-U5): callers must never report success when this is thrown. A
 * caller with a committed deduplication claim may need to retain it when delivery is uncertain.
 * Mapped to 502 Bad Gateway.
 *
 * <p>#1006: each instance carries a coarse {@link Category}. Only the category reaches the API
 * response body (information-poor per the repository error contract); the detailed message stays in
 * the server log. {@link DeliveryDisposition} is an internal retry-safety signal: callers may only
 * release a committed deduplication claim when the message is confirmed not to have been sent.
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

  /** What is known about delivery when the failure surfaced. */
  public enum DeliveryDisposition {
    /** The failure happened before dispatch, or SMTP explicitly rejected every recipient. */
    CONFIRMED_NOT_SENT,
    /** SMTP may have accepted the message before the client observed the failure. */
    DELIVERY_UNCERTAIN
  }

  private final Category category;
  private final DeliveryDisposition deliveryDisposition;

  public SmtpSendException(String message) {
    this(Category.SMTP_TRANSPORT_FAILED, message);
  }

  public SmtpSendException(String message, Throwable cause) {
    this(Category.SMTP_TRANSPORT_FAILED, message, cause);
  }

  public SmtpSendException(Category category, String message) {
    this(category, defaultDisposition(category), message);
  }

  public SmtpSendException(Category category, String message, Throwable cause) {
    this(category, defaultDisposition(category), message, cause);
  }

  public SmtpSendException(
      Category category, DeliveryDisposition deliveryDisposition, String message) {
    super(message);
    this.category = category;
    this.deliveryDisposition = deliveryDisposition;
  }

  public SmtpSendException(
      Category category, DeliveryDisposition deliveryDisposition, String message, Throwable cause) {
    super(message, cause);
    this.category = category;
    this.deliveryDisposition = deliveryDisposition;
  }

  public Category getCategory() {
    return category;
  }

  public DeliveryDisposition getDeliveryDisposition() {
    return deliveryDisposition;
  }

  public boolean isConfirmedNotSent() {
    return deliveryDisposition == DeliveryDisposition.CONFIRMED_NOT_SENT;
  }

  private static DeliveryDisposition defaultDisposition(Category category) {
    return category == Category.SMTP_TRANSPORT_FAILED
        ? DeliveryDisposition.DELIVERY_UNCERTAIN
        : DeliveryDisposition.CONFIRMED_NOT_SENT;
  }
}
