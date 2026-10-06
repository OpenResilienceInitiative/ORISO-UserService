package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.model.identity.IdentitySession;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentitySessionExchange;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
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

  @Value("${keycloak.config.admin-client-id}")
  private String keycloakAdminClientId;

  @Value("${keycloak.config.admin-client-secret}")
  private String keycloakAdminClientSecret;

  @Value("${keycloak.config.admin-service-subject}")
  private String keycloakAdminServiceSubject;

  @Value("${keycloak.config.app-client-id:app}")
  private String keycloakAppClientId;

  @Override
  public Optional<IdentitySession> exchangeForUser(String identityUserId) {
    String adminToken = loginAdminForToken();
    if (isBlank(adminToken)) {
      return Optional.empty();
    }

    try {
      MultiValueMap<String, String> form = new KeycloakAuthClient.SensitiveKeycloakFormData();
      form.add("grant_type", TOKEN_GRANT_EXCHANGE);
      form.add("client_id", keycloakAppClientId);
      form.add("subject_token", adminToken);
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

  private String loginAdminForToken() {
    try {
      MultiValueMap<String, String> form = new KeycloakAuthClient.SensitiveKeycloakFormData();
      if (isBlank(keycloakAdminClientId)
          || isBlank(keycloakAdminClientSecret)
          || keycloakAdminClientId.equals(keycloakAppClientId)) {
        return null;
      }
      form.add("grant_type", TOKEN_GRANT_CLIENT_CREDENTIALS);
      form.add("client_id", keycloakAdminClientId);
      form.add("client_secret", keycloakAdminClientSecret);
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
          || !isExpectedAdminToken(accessToken)) {
        return null;
      }
      return accessToken;
    } catch (Exception loginFailure) {
      log.warn("Identity admin-session login failed ({})", loginFailure.getClass().getSimpleName());
      return null;
    }
  }

  private boolean isExpectedAdminToken(String accessToken) {
    var jwt = jwtDecoder.decode(accessToken);
    var realmAccess = jwt.getClaimAsMap("realm_access");
    Object rolesValue = realmAccess == null ? null : realmAccess.get("roles");
    if (!(rolesValue instanceof Collection<?> roles)) {
      return false;
    }
    return !isBlank(keycloakAdminServiceSubject)
        && keycloakAdminServiceSubject.equals(jwt.getSubject())
        && keycloakAdminClientId.equals(jwt.getClaimAsString("azp"))
        && jwt.getExpiresAt() != null
        && jwt.getExpiresAt().isAfter(Instant.now())
        && roles.contains("otp-config-admin")
        && !roles.contains("technical")
        && !roles.contains("realm-admin");
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
