package de.caritas.cob.userservice.api.config.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Low-cardinality runtime measurements for synchronous outbound dependencies.
 *
 * <p>Spring Boot's observation customizer already emits {@code http.client.requests}. This
 * customizer adds the missing payload-size view and an explicitly named call/latency series that
 * groups by dependency host, HTTP method and coarse outcome. Paths, user IDs and query values are
 * deliberately never used as tags.
 */
@Component
@RequiredArgsConstructor
public class OutboundHttpMetrics implements RestTemplateCustomizer {

  static final String CALLS = "userservice.outbound.http.calls";
  static final String LATENCY = "userservice.outbound.http.latency";
  static final String PAYLOAD = "userservice.outbound.http.payload";
  static final String RETRIES = "userservice.outbound.retries";

  private static final BoundedClientRequestObservationConvention OBSERVATION_CONVENTION =
      new BoundedClientRequestObservationConvention();
  private static final Duration[] LATENCY_SLOS = {
    Duration.ofMillis(10),
    Duration.ofMillis(25),
    Duration.ofMillis(50),
    Duration.ofMillis(100),
    Duration.ofMillis(250),
    Duration.ofMillis(500),
    Duration.ofSeconds(1),
    Duration.ofSeconds(2),
    Duration.ofSeconds(5),
    Duration.ofSeconds(10),
    Duration.ofSeconds(20),
    Duration.ofSeconds(30),
    Duration.ofSeconds(60)
  };

  private final MeterRegistry meterRegistry;

  @Override
  public void customize(RestTemplate restTemplate) {
    restTemplate.setObservationConvention(OBSERVATION_CONVENTION);
    if (restTemplate.getInterceptors().stream().noneMatch(MetricsInterceptor.class::isInstance)) {
      restTemplate.getInterceptors().add(new MetricsInterceptor(this));
    }
  }

  /** Records a deliberately scheduled retry in one of the service's bounded retry loops. */
  public void recordRetry(String dependency, String operation) {
    Counter.builder(RETRIES)
        .description("Deliberately scheduled outbound dependency retries")
        .tag("dependency", dependency)
        .tag("operation", operation)
        .register(meterRegistry)
        .increment();
  }

  /**
   * Starts one measured attempt for an outbound transport that does not use {@link RestTemplate}.
   *
   * <p>The caller supplies only fixed, low-cardinality dependency and method names. Request paths,
   * identifiers and exception text are deliberately not part of this interface.
   */
  public OutboundAttempt startAttempt(String dependency, String method, long requestBytes) {
    var attempt =
        new OutboundAttempt(
            Timer.start(meterRegistry), dependency, method.toLowerCase(Locale.ROOT));
    attempt.recordRequestPayload(requestBytes);
    return attempt;
  }

  private void recordCall(Timer.Sample sample, String dependency, String method, String outcome) {
    Counter.builder(CALLS)
        .description("Outbound HTTP attempts, including attempts that fail")
        .tags("dependency", dependency, "method", method, "outcome", outcome)
        .register(meterRegistry)
        .increment();
    sample.stop(
        Timer.builder(LATENCY)
            .description("Outbound HTTP latency")
            .serviceLevelObjectives(LATENCY_SLOS)
            .tags("dependency", dependency, "method", method, "outcome", outcome)
            .register(meterRegistry));
  }

  private void recordPayload(String dependency, String direction, long bytes) {
    DistributionSummary.builder(PAYLOAD)
        .description("Outbound HTTP payload bytes when their size is known")
        .baseUnit("bytes")
        .tags("dependency", dependency, "direction", direction)
        .register(meterRegistry)
        .record(bytes);
  }

  public final class OutboundAttempt {

    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicBoolean requestPayloadRecorded = new AtomicBoolean();
    private final Timer.Sample sample;
    private final String dependency;
    private final String method;

    private OutboundAttempt(Timer.Sample sample, String dependency, String method) {
      this.sample = sample;
      this.dependency = dependency;
      this.method = method;
    }

    public void recordRequestPayload(long requestBytes) {
      if (requestBytes >= 0 && requestPayloadRecorded.compareAndSet(false, true)) {
        recordPayload(dependency, "request", requestBytes);
      }
    }

    public void complete(int status, long responseBytes) {
      if (completed.compareAndSet(false, true)) {
        recordCall(sample, dependency, method, status / 100 + "xx");
        if (responseBytes >= 0) {
          recordPayload(dependency, "response", responseBytes);
        }
      }
    }

    public void fail() {
      if (completed.compareAndSet(false, true)) {
        recordCall(sample, dependency, method, "io_error");
      }
    }
  }

  private record MetricsInterceptor(OutboundHttpMetrics metrics)
      implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(
        HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
      var dependency = dependency(request);
      var method = request.getMethod().name().toLowerCase(Locale.ROOT);
      var sample = Timer.start(metrics.meterRegistry);
      ClientHttpResponse response = null;
      String outcome = "io_error";

      try {
        response = execution.execute(request, body);
        outcome = response.getStatusCode().value() / 100 + "xx";
        return response;
      } finally {
        metrics.recordCall(sample, dependency, method, outcome);
        metrics.recordPayload(dependency, "request", body.length);
        if (response != null && response.getHeaders().getContentLength() >= 0) {
          metrics.recordPayload(dependency, "response", response.getHeaders().getContentLength());
        }
      }
    }

    private String dependency(HttpRequest request) {
      var host = request.getURI().getHost();
      return host == null || host.isBlank() ? "relative-uri" : host.toLowerCase(Locale.ROOT);
    }
  }
}
