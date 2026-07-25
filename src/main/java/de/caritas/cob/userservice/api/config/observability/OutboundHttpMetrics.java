package de.caritas.cob.userservice.api.config.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.util.Locale;
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

  private final MeterRegistry meterRegistry;

  @Override
  public void customize(RestTemplate restTemplate) {
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
        recordCall(sample, dependency, method, outcome);
        recordPayload(dependency, "request", body.length);
        if (response != null && response.getHeaders().getContentLength() >= 0) {
          recordPayload(dependency, "response", response.getHeaders().getContentLength());
        }
      }
    }

    private String dependency(HttpRequest request) {
      var host = request.getURI().getHost();
      return host == null || host.isBlank() ? "relative-uri" : host.toLowerCase(Locale.ROOT);
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
  }
}
