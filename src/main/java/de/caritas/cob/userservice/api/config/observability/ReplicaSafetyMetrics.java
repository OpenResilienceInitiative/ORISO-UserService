package de.caritas.cob.userservice.api.config.observability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Publishes the explicit replica ceiling and the bounded residual-state inventory.
 *
 * <p>Metric tags are drawn only from enums or the checked-in inventory. User, room, message and
 * token identifiers must never enter these series.
 */
@Component
public class ReplicaSafetyMetrics {

  static final String CONFIGURED_REPLICAS = "userservice.replica.configured";
  static final String SUPPORTED_MAX_REPLICAS = "userservice.replica.supported.max";
  static final String CONSTRAINT_VIOLATED = "userservice.replica.constraint.violated";
  static final String LOCAL_STATE = "userservice.replica.local_state";
  static final String LOCAL_STATE_COMPONENTS = "userservice.replica.local_state.components";
  static final String LOCAL_STATE_RISKS = "userservice.replica.local_state.risks";
  static final String SCHEDULER_REGISTERED = "userservice.scheduler.registered";

  private static final List<String> STATUSES = List.of("safe", "bounded", "blocker");
  private static final List<String> RISKS =
      List.of("performance", "correctness", "duplicate-side-effect");

  private final MeterRegistry meterRegistry;

  public ReplicaSafetyMetrics(
      MeterRegistry meterRegistry,
      ObjectMapper objectMapper,
      ResourceLoader resourceLoader,
      @Value("${userservice.replica.configured:${ORISO_USER_SERVICE_REPLICAS:1}}")
          int configuredReplicas,
      @Value("${userservice.replica.supported-max:1}") int supportedMaxReplicas) {
    this.meterRegistry = meterRegistry;
    requirePositive("configured replicas", configuredReplicas);
    requirePositive("supported replica maximum", supportedMaxReplicas);
    var components = loadInventory(objectMapper, resourceLoader);

    gauge(CONFIGURED_REPLICAS, configuredReplicas);
    gauge(SUPPORTED_MAX_REPLICAS, supportedMaxReplicas);
    gauge(CONSTRAINT_VIOLATED, configuredReplicas > supportedMaxReplicas ? 1 : 0);
    components.forEach(this::registerComponent);
    STATUSES.forEach(
        status ->
            gauge(
                LOCAL_STATE_COMPONENTS,
                "status",
                status,
                count(components, component -> status.equals(component.get("status")))));
    RISKS.forEach(
        risk ->
            gauge(
                LOCAL_STATE_RISKS,
                "risk",
                risk,
                count(components, component -> risk.equals(component.get("risk")))));
  }

  private List<Map<String, Object>> loadInventory(
      ObjectMapper objectMapper, ResourceLoader resourceLoader) {
    var resource = resourceLoader.getResource("classpath:replica-safety-components.json");
    try (var input = resource.getInputStream()) {
      var catalog = objectMapper.readTree(input);
      return objectMapper.convertValue(catalog.path("components"), new TypeReference<>() {});
    } catch (IOException exception) {
      throw new IllegalStateException("Replica-safety inventory is missing or invalid", exception);
    }
  }

  private long count(
      List<Map<String, Object>> components, Predicate<Map<String, Object>> predicate) {
    return components.stream().filter(predicate).count();
  }

  private void registerComponent(Map<String, Object> component) {
    var kind = value(component, "kind");
    if ("local-state".equals(kind)) {
      componentGauge(LOCAL_STATE, "component", component);
    } else if ("scheduler".equals(kind)) {
      componentGauge(SCHEDULER_REGISTERED, "task", component);
    } else {
      throw new IllegalStateException("Unknown replica-safety component kind: " + kind);
    }
  }

  private void componentGauge(
      String metricName, String identityTag, Map<String, Object> component) {
    Gauge.builder(metricName, () -> 1)
        .tag(identityTag, value(component, "id"))
        .tag("owner", value(component, "owner"))
        .tag("risk", value(component, "risk"))
        .tag("status", value(component, "status"))
        .register(meterRegistry);
  }

  private String value(Map<String, Object> component, String field) {
    var value = component.get(field);
    if (!(value instanceof String stringValue) || stringValue.isBlank()) {
      throw new IllegalStateException("Replica-safety component has no " + field);
    }
    return stringValue;
  }

  private void gauge(String name, int value) {
    Gauge.builder(name, () -> value).register(meterRegistry);
  }

  private void gauge(String name, String tagName, String tagValue, long value) {
    Gauge.builder(name, () -> value).tag(tagName, tagValue).register(meterRegistry);
  }

  private void requirePositive(String label, int value) {
    if (value < 1) {
      throw new IllegalArgumentException(label + " must be positive");
    }
  }
}
