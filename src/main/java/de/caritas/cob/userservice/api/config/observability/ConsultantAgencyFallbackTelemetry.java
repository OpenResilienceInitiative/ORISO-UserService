package de.caritas.cob.userservice.api.config.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Counts every consultant-agency fallback while bounding its operator warning volume.
 *
 * <p>The outbound HTTP metrics retain every dependency attempt and failure. This component adds the
 * application-level fact that the local fallback was used. Its tags are finite enums and never
 * contain consultant, agency, tenant or exception values.
 */
@Component
public class ConsultantAgencyFallbackTelemetry {

  static final String FALLBACKS = "userservice.dependency.fallbacks";
  private static final String DEPENDENCY = "agency-service";
  private static final String OPERATION = "consultant-agency-batch";

  private final Map<Reason, Counter> counters;
  private final Clock clock;
  private final long warningIntervalMillis;
  private final AtomicLong nextWarningAtMillis = new AtomicLong();
  private final AtomicLong suppressedWarnings = new AtomicLong();

  public ConsultantAgencyFallbackTelemetry(
      MeterRegistry meterRegistry,
      Clock clock,
      @Value("${userservice.dependency-fallback.warning-interval:PT1M}") Duration warningInterval) {
    this.clock = clock;
    this.warningIntervalMillis = warningInterval.toMillis();
    if (warningIntervalMillis < 1) {
      throw new IllegalArgumentException("dependency fallback warning interval must be positive");
    }

    var registeredCounters = new EnumMap<Reason, Counter>(Reason.class);
    for (var reason : Reason.values()) {
      registeredCounters.put(
          reason,
          Counter.builder(FALLBACKS)
              .tag("dependency", DEPENDENCY)
              .tag("operation", OPERATION)
              .tag("reason", reason.tagValue)
              .register(meterRegistry));
    }
    this.counters = Map.copyOf(registeredCounters);
  }

  /**
   * Records one fallback and decides whether this process may emit an operator warning.
   *
   * @return the number of warnings suppressed since the preceding emitted warning, or empty when
   *     the current fallback is suppressed
   */
  public OptionalLong record(Reason reason) {
    counters.get(reason).increment();
    var now = clock.millis();

    while (true) {
      var nextWarning = nextWarningAtMillis.get();
      if (now < nextWarning) {
        suppressedWarnings.incrementAndGet();
        return OptionalLong.empty();
      }
      if (nextWarningAtMillis.compareAndSet(nextWarning, now + warningIntervalMillis)) {
        return OptionalLong.of(suppressedWarnings.getAndSet(0));
      }
    }
  }

  public enum Reason {
    EMPTY_RESPONSE("empty-response"),
    DEPENDENCY_ERROR("dependency-error");

    private final String tagValue;

    Reason(String tagValue) {
      this.tagValue = tagValue;
    }

    public String tagValue() {
      return tagValue;
    }
  }
}
