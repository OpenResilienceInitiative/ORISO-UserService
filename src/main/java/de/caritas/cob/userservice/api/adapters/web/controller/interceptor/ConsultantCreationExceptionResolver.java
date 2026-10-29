package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import de.caritas.cob.userservice.api.service.creationoutcome.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.*;

/** Handles only creation endpoints before generic resolvers can render rejected values/causes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConsultantCreationExceptionResolver implements HandlerExceptionResolver {
  @Override
  public ModelAndView resolveException(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
    String path = request.getRequestURI();
    if (path.startsWith("/service/")) path = path.substring(8);
    if (!("POST".equals(request.getMethod()) && path.equals("/useradmin/consultants"))
        && !path.startsWith("/useradmin/consultant-creation-operations/")) return null;
    boolean tracked =
        !"POST".equals(request.getMethod())
            || request.getHeader(CreationOperationAccess.KEY_HEADER) != null;
    if (!tracked) return legacyResponse(response, failure);
    int status = 500;
    String code = "CREATION_OPERATION_FAILED";
    if (failure instanceof CreationOperationAccess.ExistingOperation existing) {
      status = 409;
      code = existing.conflict() ? "CREATION_OPERATION_CONFLICT" : "CREATION_OPERATION_REPLAY";
    } else if (failure instanceof CreationOperationFailure safe) {
      status = safe.status();
    } else if (failure instanceof HttpMessageNotReadableException
        || failure instanceof MethodArgumentNotValidException
        || failure
            instanceof org.springframework.web.method.annotation.HandlerMethodValidationException
        || failure instanceof jakarta.validation.ConstraintViolationException
        || failure
            instanceof
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) {
      status = 400;
    } else if (failure
            instanceof
            de.caritas.cob.userservice.api.exception.httpresponses
                        .CustomValidationHttpStatusException
                    validation
        && (validation.getHttpStatus().value() == 401
            || validation.getHttpStatus().value() == 403
            || validation.getHttpStatus().value() == 404)) {
      status = 404;
    } else if (failure instanceof RuntimeException runtime) {
      status = TrackedConsultantCreation.status(runtime);
    }
    if (status == 404 || status == 401 || status == 403) {
      response.setHeader(CreationOperationAccess.RECEIPT_HEADER, null);
      status = 404;
      code = "CREATION_OPERATION_NOT_FOUND";
    } else if (status == 400) code = "INVALID_CREATION_REQUEST";
    else if (status == 503) code = "CREATION_OPERATION_UNAVAILABLE";
    if (status != 404) {
      String reason = boundedReason(failure);
      if (reason != null) response.setHeader("X-Reason", reason);
    }
    response.setStatus(status);
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/json");
    try {
      response.getWriter().write("{\"code\":\"" + code + "\"}");
    } catch (IOException ignored) {
      /* The connection is already unavailable. No input is logged. */
    }
    return new ModelAndView();
  }

  /** Preserve the existing no-header status/body/reason while omitting exception diagnostics. */
  private ModelAndView legacyResponse(HttpServletResponse response, Exception failure) {
    int status =
        failure instanceof RuntimeException runtime
            ? TrackedConsultantCreation.status(runtime)
            : 500;
    Object body = null;
    if (failure instanceof de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException)
      status = 404;
    if (failure instanceof org.springframework.dao.InvalidDataAccessApiUsageException
        || failure instanceof org.springframework.dao.OptimisticLockingFailureException
        || failure instanceof jakarta.persistence.OptimisticLockException) status = 409;
    if (failure instanceof org.springframework.http.converter.HttpMessageNotReadableException
        || failure instanceof org.springframework.web.bind.MethodArgumentNotValidException
        || failure
            instanceof org.springframework.web.method.annotation.HandlerMethodValidationException
        || failure instanceof jakarta.validation.ConstraintViolationException) status = 400;
    if (failure instanceof de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException
        || failure instanceof org.springframework.security.access.AccessDeniedException)
      status = 403;
    if (failure instanceof org.springframework.web.server.ResponseStatusException expected)
      status = expected.getStatusCode().value();
    if (failure
        instanceof
        de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException
                expected) {
      status = expected.getHttpStatus().value();
      String reason = boundedReason(failure);
      if (reason != null) body = java.util.Map.of("reason", reason);
    } else if (failure
        instanceof
        de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException expected) {
      body =
          java.util.Map.of(
              "message",
              java.util.Optional.ofNullable(expected.getMessage()).orElse("BAD_REQUEST"));
    }
    String reason = boundedReason(failure);
    if (reason != null) response.setHeader("X-Reason", reason);
    response.setStatus(status);
    try {
      if (body != null) {
        response.setContentType("application/json");
        response
            .getWriter()
            .write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body));
      }
    } catch (IOException ignored) {
      /* Connection unavailable; no exception or input is logged. */
    }
    return new ModelAndView();
  }

  private String boundedReason(Exception failure) {
    if (failure
        instanceof
        de.caritas.cob.userservice.api.exception.httpresponses.ConflictException conflict)
      return conflict.getReason().map(Enum::name).orElse(null);
    if (failure
        instanceof
        de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException
                validation) {
      // The legacy header container is mutable; do not trust arbitrary values or copy its headers.
      String value = validation.getCustomHttpHeaders().getFirst("X-Reason");
      if (value != null) {
        try {
          return de.caritas.cob.userservice.api.exception.httpresponses.customheader
              .HttpStatusExceptionReason.valueOf(value)
              .name();
        } catch (IllegalArgumentException ignored) {
          return null;
        }
      }
    }
    return null;
  }
}
