package de.caritas.cob.userservice.api.exception.httpresponses;

import de.caritas.cob.userservice.api.service.LogService;
import java.util.function.Consumer;

/**
 * 503 - the requested resource is temporarily locked or unavailable and the caller should retry.
 */
public class ServiceUnavailableException extends CustomHttpStatusException {

  private static final long serialVersionUID = 1L;

  /**
   * Service unavailable exception.
   *
   * @param message the message
   */
  public ServiceUnavailableException(String message) {
    super(message, LogService::logWarn);
  }

  /**
   * Service unavailable exception.
   *
   * @param message an additional message
   * @param loggingMethod the method being used to log this exception
   */
  public ServiceUnavailableException(String message, Consumer<Exception> loggingMethod) {
    super(message, loggingMethod);
  }
}
