package de.caritas.cob.userservice.api.adapters.web.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Best-effort client-side error report submitted by an unauthenticated frontend/admin client (e.g.
 * from a top-level React ErrorBoundary). See OBS-P3 / ORISO-Helm#62: these reports are logged
 * through the same structured JSON logger the backend already uses for OTLP/SigNoz (OBS-P2), tagged
 * with a {@code client_error_source} MDC field so they can be filtered separately from
 * backend-originated log lines.
 */
@Getter
@Setter
public class ErrorReportDTO {

  /** Expected values: "frontend" or "admin". Anything else is logged as "unknown". */
  @Size(max = 32)
  private String source;

  @Size(max = 4000)
  private String message;

  @Size(max = 8000)
  private String stack;

  @Size(max = 2000)
  private String url;

  @Size(max = 300)
  private String userAgent;

  @Size(max = 100)
  private String correlationId;

  /** Expected values: "error" or "warn". Defaults to "error" when absent/unrecognized. */
  @Size(max = 16)
  private String severity;
}
