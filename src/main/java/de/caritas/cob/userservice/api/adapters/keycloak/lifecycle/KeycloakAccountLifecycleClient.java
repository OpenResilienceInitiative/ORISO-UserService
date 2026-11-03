package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Narrow client for the default-off Keycloak account lifecycle receiver.
 *
 * <p>The client is intentionally not connected to invitation workflows yet. It establishes the
 * exact authenticated wire contract before those workflows stop using the legacy Keycloak admin
 * paths.
 */
@Component
public class KeycloakAccountLifecycleClient {
  private final RestTemplate restTemplate;
  private final KeycloakConfig keycloak;
  private final IdentityClientConfig identityClientConfig;
  private final TaskActorConfiguration taskActors;
  private final TaskContextProofSigner proofSigner;

  public KeycloakAccountLifecycleClient(
      @Qualifier("keycloakRestTemplate") RestTemplate restTemplate,
      KeycloakConfig keycloak,
      IdentityClientConfig identityClientConfig,
      TaskActorConfiguration taskActors,
      TaskContextProofSigner proofSigner) {
    this.restTemplate = restTemplate;
    this.keycloak = keycloak;
    this.identityClientConfig = identityClientConfig;
    this.taskActors = taskActors;
    this.proofSigner = proofSigner;
  }

  public JsonNode create(UUID attemptId, JsonNode body) {
    return invoke(
        TaskPurpose.ACCOUNT_PROVISIONING, "account.create", HttpMethod.PUT, attemptId, "", body);
  }

  public JsonNode ownedRead(UUID attemptId, JsonNode body) {
    return invoke(
        TaskPurpose.ACCOUNT_PROVISIONING,
        "account.owned-read",
        HttpMethod.POST,
        attemptId,
        "/owned-read",
        body);
  }

  /**
   * Recovers an interrupted lifecycle attempt under the explicitly selected account actor.
   *
   * <p>Both account actors may recover an attempt, but callers must make that authority choice
   * explicit rather than receiving an implicit privileged default.
   */
  public JsonNode recover(TaskPurpose purpose, UUID attemptId, JsonNode body) {
    requireAccountPurpose(purpose);
    return invoke(
        purpose, "account.creation-recover", HttpMethod.POST, attemptId, "/recover", body);
  }

  public JsonNode commit(UUID attemptId, JsonNode body) {
    return invoke(
        TaskPurpose.ACCOUNT_PROVISIONING,
        "account.commit",
        HttpMethod.POST,
        attemptId,
        "/commit",
        body);
  }

  /** Compensates an interrupted lifecycle attempt under the explicitly selected account actor. */
  public JsonNode compensate(TaskPurpose purpose, UUID attemptId, JsonNode body) {
    requireAccountPurpose(purpose);
    return invoke(purpose, "account.compensate", HttpMethod.POST, attemptId, "/compensate", body);
  }

  private JsonNode invoke(
      TaskPurpose purpose,
      String operation,
      HttpMethod method,
      UUID attemptId,
      String suffix,
      JsonNode body) {
    var binding = taskActors.require(purpose);
    String proof = proofSigner.sign(purpose, binding, operation, method.name(), attemptId, body);
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(identityClientConfig.taskAccessToken(purpose));
    headers.set("X-ORISO-Task-Context", proof);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return restTemplate
        .exchange(
            endpoint(attemptId, suffix), method, new HttpEntity<>(body, headers), JsonNode.class)
        .getBody();
  }

  private URI endpoint(UUID attemptId, String suffix) {
    if (attemptId == null) throw new IllegalArgumentException("Lifecycle attempt ID is required");
    String base = keycloak.getAuthServerUrl();
    if (base == null || base.isBlank()) {
      throw new IllegalStateException("Keycloak auth server URL is required");
    }
    return URI.create(
        base.replaceAll("/+$", "")
            + "/realms/"
            + keycloak.getRealm()
            + "/oriso-account-lifecycle/v1/attempts/"
            + attemptId
            + suffix);
  }

  private static void requireAccountPurpose(TaskPurpose purpose) {
    if (purpose != TaskPurpose.ACCOUNT_PROVISIONING && purpose != TaskPurpose.ACCOUNT_MAINTENANCE) {
      throw new IllegalArgumentException("Lifecycle requests require an account task purpose");
    }
  }
}
