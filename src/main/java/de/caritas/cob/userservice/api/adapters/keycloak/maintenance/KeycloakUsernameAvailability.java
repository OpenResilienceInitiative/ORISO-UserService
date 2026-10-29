package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.port.out.ScopedAvailabilityUnavailableException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Exact username-only read; no native query projection or alternate actor fallback. */
@RequiredArgsConstructor
public final class KeycloakUsernameAvailability implements ScopedUsernameAvailability {
  private final @NonNull RestTemplate http;
  private final @NonNull TaskActorConfiguration actors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull AccountLifecycleCodec codec;
  private final @NonNull String authServer;
  private final @NonNull String realmName;
  private final @NonNull String realmId;

  @Override
  public boolean available(String exactUsername) {
    try {
      if (exactUsername == null
          || exactUsername.isBlank()
          || exactUsername.length() > 255
          || exactUsername.chars().anyMatch(Character::isISOControl))
        throw new ScopedAvailabilityUnavailableException();
      var actor = actors.require(TaskPurpose.ACCOUNT_PROVISIONING);
      var token = broker.issueCheckedProvisioning(TaskPurpose.ACCOUNT_PROVISIONING);
      if (token.actor() != actor.actor()) throw new ScopedAvailabilityUnavailableException();
      var body = Map.of("selector", "username", "value", exactUsername);
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setAccept(List.of(MediaType.APPLICATION_JSON));
      headers.setCacheControl("no-store");
      headers.setBearerAuth(token.accessToken());
      headers.set(
          "X-ORISO-Task-Context", codec.usernameAvailabilityProof(realmName, realmId, actor, body));
      var uri =
          UriComponentsBuilder.fromUriString(authServer)
              .pathSegment("realms", realmName, "oriso-account-maintenance", "v1", "availability")
              .build()
              .encode()
              .toUri();
      var response =
          http.exchange(
              uri,
              HttpMethod.POST,
              new HttpEntity<>(codec.canonical(body).getBytes(StandardCharsets.UTF_8), headers),
              byte[].class);
      byte[] raw = response.getBody();
      if (response.getStatusCode().value() != 200 || raw == null || raw.length > 128)
        throw new ScopedAvailabilityUnavailableException();
      var result = codec.read(codec.utf8(raw), com.fasterxml.jackson.databind.JsonNode.class);
      if (!result.isObject() || result.size() != 1 || !result.path("available").isBoolean())
        throw new ScopedAvailabilityUnavailableException();
      return result.path("available").booleanValue();
    } catch (RuntimeException unavailable) {
      // Provider bodies, credentials, tokens and exact username values do not cross this boundary.
      throw new ScopedAvailabilityUnavailableException();
    }
  }
}
