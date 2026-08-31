package de.caritas.cob.userservice.api.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ReplicaSafetyInventoryContractTest {

  private static final Set<String> REQUIRED_COMPONENTS =
      Set.of(
          "email-template-cache",
          "matrix-access-token-cache",
          "matrix-browser-login-locks",
          "matrix-sync-token-cache",
          "matrix-presence-cache",
          "matrix-admin-token-cache",
          "matrix-impersonation-token-cache",
          "matrix-room-session-map",
          "matrix-room-users-map",
          "matrix-sync-loop",
          "notification-active-view-map",
          "caffeine-cache-manager",
          "agency-cache",
          "consulting-type-cache",
          "application-settings-cache",
          "tenant-cache",
          "tenant-admin-cache",
          "topics-cache",
          "operator-dpa-content-cache",
          "appointment-cleanup-scheduler",
          "enquiry-notification-scheduler",
          "group-chat-deactivation-scheduler",
          "anonymous-deactivation-scheduler",
          "group-chat-reminder-scheduler",
          "inactive-account-notification-scheduler",
          "account-deletion-scheduler",
          "anonymous-deletion-scheduler",
          "registered-only-deletion-scheduler",
          "handshake-expiry-scheduler",
          "support-room-expiry-scheduler",
          "event-notification-retention-scheduler");

  @Test
  void shouldInventoryEveryKnownReplicaLocalComponentWithAnActionableSignal() throws Exception {
    var resource =
        getClass().getClassLoader().getResourceAsStream("replica-safety-components.json");

    assertThat(resource).as("replica-safety inventory").isNotNull();

    var objectMapper = new ObjectMapper();
    var catalog = objectMapper.readTree(resource);
    assertThat(catalog.path("targetArchitecture").asText())
        .isEqualTo(
            "Matrix chat + ORISO frontend + ORISO-controlled Element Call/MatrixRTC fork + LiveKit");
    List<Map<String, Object>> components =
        objectMapper.convertValue(catalog.path("components"), new TypeReference<>() {});
    var ids =
        components.stream()
            .map(component -> (String) component.get("id"))
            .collect(Collectors.toSet());

    assertThat(ids).containsExactlyInAnyOrderElementsOf(REQUIRED_COMPONENTS);
    assertThat(ids).hasSameSizeAs(components);
    assertThat(components)
        .allSatisfy(
            component -> {
              assertThat(component)
                  .containsKeys(
                      "id",
                      "kind",
                      "source",
                      "owner",
                      "risk",
                      "decision",
                      "components",
                      "signals",
                      "status");
              assertThat(component.get("id")).asString().isNotBlank();
              assertThat(component.get("source")).asString().isNotBlank();
              assertThat(component.get("owner")).asString().isNotBlank();
              assertThat(component.get("decision")).asString().isNotBlank();
              assertThat((List<?>) component.get("components")).isNotEmpty();
              assertThat((List<?>) component.get("signals")).isNotEmpty();
              assertThat(component.get("kind")).isIn("local-state", "scheduler");
              assertThat(component.get("risk"))
                  .isIn("performance", "correctness", "duplicate-side-effect");
              assertThat(component.get("status")).isIn("safe", "bounded", "blocker");
              assertThat(component.toString().toLowerCase())
                  .doesNotContain("rocket.chat", "rocketchat", "jitsi");
            });
  }
}
