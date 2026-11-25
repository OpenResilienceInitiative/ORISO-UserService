package de.caritas.cob.userservice.api.config.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Signals for Teamberatung read-only enforcement (ORISO-Frontend#2494 *4). A team case stays
 * pending while its centre's case sharing model cannot be resolved; a rising series means existing
 * rooms are not being converted.
 *
 * <p>Tags come from fixed values only. Session, agency and tenant ids never enter a metric series.
 */
@Component
@RequiredArgsConstructor
public class TeamSessionReadOnlyMetrics {

  static final String PENDING = "oriso.team_read_only.pending";

  private final MeterRegistry meterRegistry;

  /** The centre's mode could not be read or was not served. */
  public void recordModeUnresolved() {
    counter("mode_unresolved");
  }

  /** The team case has no centre, so its mode cannot be resolved at all. */
  public void recordNoCentre() {
    counter("no_centre");
  }

  private void counter(String reason) {
    try {
      Counter.builder(PENDING).tags("reason", reason).register(meterRegistry).increment();
    } catch (RuntimeException ignored) {
      // Telemetry must never change enforcement.
    }
  }
}
