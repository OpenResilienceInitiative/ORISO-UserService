package de.caritas.cob.userservice.api.config.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReplicaSafetyMetrics {

  private static final String CATALOG_RESOURCE = "/replica-safety-components.json";

  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;
  private final List<AtomicInteger> retainedGaugeValues = new ArrayList<>();

  @PostConstruct
  void registerCatalog() {
    try (var catalogStream = ReplicaSafetyMetrics.class.getResourceAsStream(CATALOG_RESOURCE)) {
      if (catalogStream == null) {
        throw new IllegalStateException("Missing replica safety catalog");
      }
      var catalog = objectMapper.readTree(catalogStream);
      var metricName = catalog.path("runtimeMetric").asText();
      var schedulerMetricName = catalog.path("schedulerRegistrationMetric").asText();
      for (var component : catalog.path("components")) {
        if ("local-state".equals(component.path("kind").asText())) {
          registerLocalStateGauge(
              metricName,
              component.path("id").asText(),
              component.path("owner").asText(),
              component.path("risk").asText());
        } else if ("scheduler".equals(component.path("kind").asText())) {
          registerSchedulerGauge(
              schedulerMetricName,
              component.path("id").asText(),
              component.path("owner").asText(),
              component.path("risk").asText());
        }
      }
      registerGauge("userservice.replica.max_supported", "Maximum currently supported replicas");
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read replica safety catalog", exception);
    }
  }

  private void registerLocalStateGauge(
      String metricName, String component, String owner, String risk) {
    var value = retainedValue();
    Gauge.builder(metricName, value, AtomicInteger::get)
        .description("Presence of a classified process-local state component")
        .tag("component", component)
        .tag("owner", owner)
        .tag("risk", risk)
        .register(meterRegistry);
  }

  private void registerSchedulerGauge(String metricName, String task, String owner, String risk) {
    var value = retainedValue();
    Gauge.builder(metricName, value, AtomicInteger::get)
        .description("Presence of a classified scheduled task")
        .tag("task", task)
        .tag("owner", owner)
        .tag("risk", risk)
        .register(meterRegistry);
  }

  private void registerGauge(String metricName, String description) {
    var value = retainedValue();
    Gauge.builder(metricName, value, AtomicInteger::get)
        .description(description)
        .register(meterRegistry);
  }

  private AtomicInteger retainedValue() {
    var value = new AtomicInteger(1);
    retainedGaugeValues.add(value);
    return value;
  }
}
