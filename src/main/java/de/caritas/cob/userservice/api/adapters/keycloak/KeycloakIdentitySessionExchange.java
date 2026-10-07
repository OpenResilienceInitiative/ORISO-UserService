package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.model.identity.IdentitySession;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentitySessionExchange;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/** Keycloak token-exchange adapter for trusted identity subjects. */
@Component
@RequiredArgsConstructor
@Slf4j
public class KeycloakIdentitySessionExchange implements IdentitySessionExchange {

  private static final String TOKEN_ENDPOINT_PATH = "/token";
  private static final String TOKEN_GRANT_CLIENT_CREDENTIALS = "client_credentials";
  private static final String TOKEN_GRANT_EXCHANGE =
      "urn:ietf:params:oauth:grant-type:token-exchange";
  private final @NonNull RestTemplate restTemplate;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull JwtDecoder jwtDecoder;

  @Value("${identity.tasks.session-exchange.client-id}")
  private String sessionExchangeClientId;

  @Value("${identity.tasks.session-exchange.client-secret}")
  private String sessionExchangeClientSecret;

  @Value("${identity.tasks.session-exchange.service-subject}")
  private String sessionExchangeServiceSubject;

  @Value("${keycloak.config.app-client-id:app}")
  private String keycloakAppClientId;

  @Override
  public Optional<IdentitySession> exchangeForUser(String identityUserId) {
    String taskToken = loginSessionExchangeForToken();
    if (isBlank(taskToken)) {
      return Optional.empty();
    }

    try {
      MultiValueMap<String, String> form = new KeycloakAuthClient.SensitiveKeycloakFormData();
      form.add("grant_type", TOKEN_GRANT_EXCHANGE);
      form.add("client_id", keycloakAppClientId);
      form.add("subject_token", taskToken);
      form.add("requested_subject", identityUserId);
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
      String tokenUrl = identityClientConfig.getOpenIdConnectUrl(TOKEN_ENDPOINT_PATH);
      var response =
          restTemplate.postForEntity(tokenUrl, entity, KeycloakLoginResponseDTO.class).getBody();
      return Optional.ofNullable(response).map(KeycloakIdentitySessionExchange::toIdentitySession);
    } catch (Exception exchangeFailure) {
      log.warn("Identity session exchange failed ({})", exchangeFailure.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private String loginSessionExchangeForToken() {
    try {
      MultiValueMap<String, String> form = new KeycloakAuthClient.SensitiveKeycloakFormData();
      if (isBlank(sessionExchangeClientId)
          || isBlank(sessionExchangeClientSecret)
          || sessionExchangeClientId.equals(keycloakAppClientId)) {
        return null;
      }
      form.add("grant_type", TOKEN_GRANT_CLIENT_CREDENTIALS);
      form.add("client_id", sessionExchangeClientId);
      form.add("client_secret", sessionExchangeClientSecret);
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
      String tokenUrl = identityClientConfig.getOpenIdConnectUrl(TOKEN_ENDPOINT_PATH);
      var response = restTemplate.postForEntity(tokenUrl, entity, Map.class);
      if (response.getBody() == null) {
        return null;
      }
      Object token = response.getBody().get("access_token");
      if (!(token instanceof String accessToken)
          || isBlank(accessToken)
          || !isExpectedSessionExchangeToken(accessToken)) {
        return null;
      }
      return accessToken;
    } catch (Exception loginFailure) {
      log.warn(
          "Identity session-exchange login failed ({})", loginFailure.getClass().getSimpleName());
      return null;
    }
  }

  private boolean isExpectedSessionExchangeToken(String accessToken) {
    var jwt = jwtDecoder.decode(accessToken);
    var realmAccess = jwt.getClaimAsMap("realm_access");
    Object rolesValue = realmAccess == null ? null : realmAccess.get("roles");
    if (!(rolesValue instanceof Collection<?> roles)) {
      return false;
    }
    return Set.of("session-exchange").equals(new HashSet<>(roles))
        && !isBlank(sessionExchangeServiceSubject)
        && sessionExchangeServiceSubject.equals(jwt.getSubject())
        && sessionExchangeClientId.equals(jwt.getClaimAsString("azp"))
        && jwt.getExpiresAt() != null
        && jwt.getExpiresAt().isAfter(Instant.now())
        && hasNoPrivilegedResources(jwt.getClaimAsMap("resource_access"));
  }

  private static boolean hasNoPrivilegedResources(Map<String, Object> resourceAccess) {
    return resourceAccess == null || resourceAccess.isEmpty();
  }

  private static IdentitySession toIdentitySession(KeycloakLoginResponseDTO response) {
    return new IdentitySession(
        response.getAccessToken(),
        response.getExpiresIn(),
        response.getRefreshExpiresIn(),
        response.getRefreshToken(),
        response.getTokenType(),
        response.getSessionState(),
        response.getScope());
  }
}
