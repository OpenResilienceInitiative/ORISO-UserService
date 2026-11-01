package de.caritas.cob.userservice.api.adapters.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Handles only this operation's input errors before generic handlers can log rejected selectors.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdentityObservationInputExceptionResolver implements HandlerExceptionResolver {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Override
  public ModelAndView resolveException(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler,
      Exception exception) {
    if (!(handler instanceof HandlerMethod method)
        || !UserAdminController.class.isAssignableFrom(method.getBeanType())
        || !method.getMethod().getName().equals("observeIdentityOccupancy")
        || !(exception instanceof HttpMessageNotReadableException
            || exception instanceof MethodArgumentNotValidException
            || exception instanceof HandlerMethodValidationException
            || exception instanceof ConstraintViolationException)) {
      return null;
    }
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setHeader("Cache-Control", "no-store");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    try {
      JSON.writeValue(
          response.getOutputStream(),
          Map.of("message", IdentityObservationHttpAdapter.INVALID_SELECTOR_MESSAGE));
    } catch (IOException writeFailure) {
      // Propagate only the response I/O failure; never hand the rejected input to generic logging.
      throw new UncheckedIOException("Could not write identity validation response", writeFailure);
    }
    return new ModelAndView();
  }
}
