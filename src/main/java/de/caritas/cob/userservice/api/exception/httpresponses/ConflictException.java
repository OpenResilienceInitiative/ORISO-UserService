package de.caritas.cob.userservice.api.exception.httpresponses;

import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.service.LogService;
import java.util.Optional;
import java.util.function.Consumer;

public class ConflictException extends CustomHttpStatusException {

  private static final long serialVersionUID = 1L;

  private final transient HttpStatusExceptionReason reason;

  /**
   * Conflict exception.
   *
   * @param message the message
   */
  public ConflictException(String message) {
    super(message, LogService::logInternalServerError);
    this.reason = null;
  }

  /**
   * Conflict exception with a machine-readable reason, sent to the client in the {@code X-Reason}
   * header so it can tell this conflict from any other 409.
   *
   * @param message an additional message
   * @param reason the reason the client may act on
   */
  public ConflictException(String message, HttpStatusExceptionReason reason) {
    super(message, LogService::logInternalServerError);
    this.reason = reason;
  }

  /**
   * The machine-readable reason, if one was given.
   *
   * @return the reason
   */
  public Optional<HttpStatusExceptionReason> getReason() {
    return Optional.ofNullable(reason);
  }

  /**
   * Conflict exception.
   *
   * @param message an additional message
   * @param loggingMethod the method being used to log this exception
   */
  public ConflictException(String message, Consumer<Exception> loggingMethod) {
    super(message, loggingMethod);
    this.reason = null;
  }
}
