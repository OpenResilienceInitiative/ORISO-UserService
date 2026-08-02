package de.caritas.cob.userservice.api.exception.httpresponses;

import de.caritas.cob.userservice.api.service.LogService;
import java.util.function.Consumer;

/**
 * The addressed thing existed but its window has closed — ADR-018 answers 410 for a lapsed support
 * request so the caller can tell "too late" apart from "never existed" (404) and "already decided"
 * (409).
 */
public class GoneException extends CustomHttpStatusException {

  private static final long serialVersionUID = 1L;

  public GoneException(String message) {
    super(message, LogService::logInternalServerError);
  }

  public GoneException(String message, Consumer<Exception> loggingMethod) {
    super(message, loggingMethod);
  }
}
