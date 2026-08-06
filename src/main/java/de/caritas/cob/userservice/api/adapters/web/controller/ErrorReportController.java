package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.apache.commons.lang3.StringUtils.isAllBlank;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.web.dto.ErrorReportDTO;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Intake for client-side error reports from the Frontend and Admin apps (OBS-P3, ORISO-Helm#62).
 *
 * <p>Deliberately unauthenticated: a client-side crash can happen before login completes, so this
 * cannot require a bearer token. Reports are logged via the same SLF4J/Logback pipeline the rest of
 * the backend already uses, which OBS-P2 wired to emit structured JSON (with trace/span
 * correlation) to stdout for SigNoz's filelog receiver to pick up - no separate OTLP/logging path
 * is introduced here. Each report is tagged with an MDC {@code client_error_source} (and {@code
 * client_error_severity}) field so it renders as a top-level JSON field, making it trivial to
 * filter client-originated log lines apart from backend-originated ones in SigNoz.
 *
 * <p>Best-effort by design: this is telemetry intake, not a critical path. Anything short of a
 * completely empty/unusable payload is logged and acknowledged with 202; only a payload with
 * nothing worth logging is rejected with 400. See {@link
 * de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ErrorReportBodySizeLimitFilter}
 * for the request-size abuse guard applied to this path.
 */
@Slf4j
@RestController
@RequestMapping({"", "/service"})
public class ErrorReportController {

  private static final int MAX_MESSAGE_LENGTH = 4000;
  private static final int MAX_STACK_LENGTH = 8000;
  private static final int MAX_URL_LENGTH = 2000;
  private static final int MAX_USER_AGENT_LENGTH = 300;
  private static final int MAX_CORRELATION_ID_LENGTH = 100;

  private static final Set<String> ALLOWED_SOURCES = Set.of("frontend", "admin");
  private static final Set<String> ALLOWED_SEVERITIES = Set.of("error", "warn");

  private static final String MDC_SOURCE_KEY = "client_error_source";
  private static final String MDC_SEVERITY_KEY = "client_error_severity";

  @PostMapping("/error-reports")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ResponseEntity<Void> reportClientError(
      @RequestBody(required = false) ErrorReportDTO report) {
    if (report == null || isAllBlank(report.getMessage(), report.getUrl(), report.getStack())) {
      log.debug("Rejecting client error report with no usable content.");
      return ResponseEntity.badRequest().build();
    }

    var source = normalize(report.getSource(), ALLOWED_SOURCES, "unknown");
    var severity = normalize(report.getSeverity(), ALLOWED_SEVERITIES, "error");

    MDC.put(MDC_SOURCE_KEY, source);
    MDC.put(MDC_SEVERITY_KEY, severity);
    try {
      var message = truncate(report.getMessage(), MAX_MESSAGE_LENGTH);
      var url = truncate(report.getUrl(), MAX_URL_LENGTH);
      var userAgent = truncate(report.getUserAgent(), MAX_USER_AGENT_LENGTH);
      var correlationId = truncate(report.getCorrelationId(), MAX_CORRELATION_ID_LENGTH);
      var stack = truncate(report.getStack(), MAX_STACK_LENGTH);

      if ("warn".equals(severity)) {
        log.warn(
            "Client-reported error: source={}, message={}, url={}, userAgent={},"
                + " correlationId={}, stack={}",
            source,
            message,
            url,
            userAgent,
            correlationId,
            stack);
      } else {
        log.error(
            "Client-reported error: source={}, message={}, url={}, userAgent={},"
                + " correlationId={}, stack={}",
            source,
            message,
            url,
            userAgent,
            correlationId,
            stack);
      }
    } finally {
      MDC.remove(MDC_SOURCE_KEY);
      MDC.remove(MDC_SEVERITY_KEY);
    }

    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }

  private static String normalize(String value, Set<String> allowed, String fallback) {
    if (isBlank(value)) {
      return fallback;
    }
    var candidate = value.trim().toLowerCase();
    return allowed.contains(candidate) ? candidate : fallback;
  }

  private static String truncate(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    return value.length() > maxLength ? value.substring(0, maxLength) : value;
  }
}
