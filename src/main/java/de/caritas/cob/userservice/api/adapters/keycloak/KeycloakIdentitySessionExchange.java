package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.model.identity.IdentitySession;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentitySessionExchange;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/** Keycloak token-exchange adapter for trusted identity subjects. */
@Component
@RequiredArgsConstructor
@Slf4j
public class KeycloakIdentitySessionExchange implements IdentitySessionExchange {

  private static final String TOKEN_ENDPOINT_PATH = "/token";
  private static final String TOKEN_GRANT_PASSWORD = "password";
  private static final String TOKEN_GRANT_EXCHANGE =
      "urn:ietf:params:oauth:grant-type:token-exchange";

  private final @NonNull RestTemplate restTemplate;
  private final @NonNull IdentityClientConfig identityClientConfig;

  @Value("${keycloak.config.admin-username}")
  private String keycloakAdminUsername;

  @Value("${keycloak.config.admin-password}")
  private String keycloakAdminPassword;

  @Value("${keycloak.config.app-client-id:app}")
  private String keycloakAppClientId;

  @Override
  public Optional<IdentitySession> exchangeForUser(String identityUserId) {
    String adminToken = loginAdminForToken();
    if (isBlank(adminToken)) {
      return Optional.empty();
    }

    try {
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
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
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("grant_type", TOKEN_GRANT_PASSWORD);
      form.add("client_id", keycloakAppClientId);
      form.add("username", keycloakAdminUsername);
      form.add("password", keycloakAdminPassword);
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
      String tokenUrl = identityClientConfig.getOpenIdConnectUrl(TOKEN_ENDPOINT_PATH);
      var response = restTemplate.postForEntity(tokenUrl, entity, Map.class);
      if (response.getBody() == null) {
        return null;
      }
      Object token = response.getBody().get("access_token");
      return token == null ? null : String.valueOf(token);
    } catch (Exception loginFailure) {
      log.warn("Identity admin-session login failed ({})", loginFailure.getClass().getSimpleName());
      return null;
    }
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
