package de.caritas.cob.userservice.api.config.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
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
    restTemplate.getInterceptors().add(new MetricsInterceptor(meterRegistry));
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
   * Starts a measurement for an outbound client that does not use {@link RestTemplate}.
   *
   * <p>The returned attempt is safe to complete from an asynchronous callback. Only the first
   * completion is recorded. URI paths and query parameters never become metric tags.
   *
   * @param uri target URI used only to derive the dependency host
   * @param method HTTP method
   * @param requestBytes serialized request size, or a negative value when unknown
   * @return the in-flight measurement
   */
  public OutboundHttpCall startHttpCall(URI uri, String method, long requestBytes) {
    var dependency = dependency(uri);
    var normalizedMethod =
        method == null || method.isBlank() ? "unknown" : method.toLowerCase(Locale.ROOT);
    if (requestBytes >= 0) {
      recordPayload(dependency, "request", requestBytes);
    }
    return new OutboundHttpCall(dependency, normalizedMethod, Timer.start(meterRegistry));
  }

  /** Records the serialized request size for a client whose writer runs after the call starts. */
  public void recordRequestPayload(URI uri, long requestBytes) {
    if (requestBytes >= 0) {
      recordPayload(dependency(uri), "request", requestBytes);
    }
  }

  /** One asynchronously completed outbound HTTP attempt. */
  public final class OutboundHttpCall {

    private final String dependency;
    private final String method;
    private final Timer.Sample sample;
    private final AtomicBoolean completed = new AtomicBoolean();

    private OutboundHttpCall(String dependency, String method, Timer.Sample sample) {
      this.dependency = dependency;
      this.method = method;
      this.sample = sample;
    }

    /**
     * Completes the attempt with an HTTP response.
     *
     * @param statusCode HTTP response status
     * @param responseBytes serialized response size, or a negative value when unknown
     */
    public void completeWithStatus(int statusCode, long responseBytes) {
      complete(statusCode > 0 ? statusCode / 100 + "xx" : "unknown", responseBytes);
    }

    /** Completes the attempt after a transport failure without an HTTP response. */
    public void completeWithTransportError() {
      complete("io_error", -1);
    }

    private void complete(String outcome, long responseBytes) {
      if (!completed.compareAndSet(false, true)) {
        return;
      }
      recordCall(sample, dependency, method, outcome);
      if (responseBytes >= 0) {
        recordPayload(dependency, "response", responseBytes);
      }
    }
  }

  private String dependency(URI uri) {
    var host = uri == null ? null : uri.getHost();
    return host == null || host.isBlank() ? "relative-uri" : host.toLowerCase(Locale.ROOT);
  }

  private void recordCall(Timer.Sample sample, String dependency, String method, String outcome) {
    recordCall(meterRegistry, sample, dependency, method, outcome);
  }

  private static void recordCall(
      MeterRegistry meterRegistry,
      Timer.Sample sample,
      String dependency,
      String method,
      String outcome) {
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
    recordPayload(meterRegistry, dependency, direction, bytes);
  }

  private static void recordPayload(
      MeterRegistry meterRegistry, String dependency, String direction, long bytes) {
    DistributionSummary.builder(PAYLOAD)
        .description("Outbound HTTP payload bytes when their size is known")
        .baseUnit("bytes")
        .tags("dependency", dependency, "direction", direction)
        .register(meterRegistry)
        .record(bytes);
  }

  private record MetricsInterceptor(MeterRegistry meterRegistry)
      implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(
        HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
      var dependency = dependency(request);
      var method = request.getMethod().name().toLowerCase(Locale.ROOT);
      var sample = Timer.start(meterRegistry);
      ClientHttpResponse response = null;
      String outcome = "io_error";

      try {
        response = execution.execute(request, body);
        outcome = response.getStatusCode().value() / 100 + "xx";
        return response;
      } finally {
        OutboundHttpMetrics.recordCall(meterRegistry, sample, dependency, method, outcome);
        OutboundHttpMetrics.recordPayload(meterRegistry, dependency, "request", body.length);
        if (response != null && response.getHeaders().getContentLength() >= 0) {
          OutboundHttpMetrics.recordPayload(
              meterRegistry, dependency, "response", response.getHeaders().getContentLength());
        }
      }
    }

    private String dependency(HttpRequest request) {
      var host = request.getURI().getHost();
      return host == null || host.isBlank() ? "relative-uri" : host.toLowerCase(Locale.ROOT);
    }
  }
}
