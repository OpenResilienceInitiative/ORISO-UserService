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
    var localStateComponents =
        components.stream().filter(component -> hasKind(component, "local-state")).toList();

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
                count(localStateComponents, component -> status.equals(component.get("status")))));
    RISKS.forEach(
        risk ->
            gauge(
                LOCAL_STATE_RISKS,
                "risk",
                risk,
                count(localStateComponents, component -> risk.equals(component.get("risk")))));
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
      componentGauge(LOCAL_STATE, "component", value(component, "id"), component);
    } else if ("scheduler".equals(kind)) {
      values(component, "components")
          .forEach(
              method ->
                  componentGauge(
                      SCHEDULER_REGISTERED, "task", schedulerTask(component, method), component));
    } else {
      throw new IllegalStateException("Unknown replica-safety component kind: " + kind);
    }
  }

  private void componentGauge(
      String metricName, String identityTag, String identityValue, Map<String, Object> component) {
    Gauge.builder(metricName, () -> 1)
        .tag(identityTag, identityValue)
        .tag("owner", value(component, "owner"))
        .tag("risk", value(component, "risk"))
        .tag("status", value(component, "status"))
        .register(meterRegistry);
  }

  private boolean hasKind(Map<String, Object> component, String kind) {
    return kind.equals(component.get("kind"));
  }

  private String schedulerTask(Map<String, Object> component, String method) {
    var source = value(component, "source");
    var fileName = source.substring(source.lastIndexOf('/') + 1);
    if (!fileName.endsWith(".java")) {
      throw new IllegalStateException("Scheduler source is not a Java file: " + source);
    }
    return fileName.substring(0, fileName.length() - ".java".length()) + "." + method + "()";
  }

  private List<String> values(Map<String, Object> component, String field) {
    var values = component.get(field);
    if (!(values instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalStateException("Replica-safety component has no " + field);
    }
    return list.stream()
        .map(
            value -> {
              if (!(value instanceof String stringValue) || stringValue.isBlank()) {
                throw new IllegalStateException("Replica-safety component has invalid " + field);
              }
              return stringValue;
            })
        .toList();
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
