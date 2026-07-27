package de.caritas.cob.userservice.api.adapters.keycloak;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import jakarta.ws.rs.BadRequestException;
import java.util.Collections;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * Keycloak OpenID Connect / session operations extracted from {@link KeycloakService} (issue #91
 * Keycloak refactor, PR1 auth slice). Keeps login, refresh-token logout and password verification
 * behind a focused collaborator.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeycloakAuthClient {

  private static final String KEYCLOAK_GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
  private static final String KEYCLOAK_GRANT_TYPE_PASSWORD = "password";
  private static final String BODY_KEY_CLIENT_ID = "client_id";
  private static final String BODY_KEY_GRANT_TYPE = "grant_type";
  private static final String BODY_KEY_PASSWORD = "password";
  private static final String BODY_KEY_USERNAME = "username";
  private static final String ENDPOINT_OPENID_CONNECT_LOGIN = "/token";
  private static final String ENDPOINT_OPENID_CONNECT_LOGOUT = "/logout";

  private final @NonNull RestTemplate restTemplate;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull KeycloakClient keycloakClient;

  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  @Value("${keycloak.config.app-client-id}")
  private String keycloakClientId;

  /**
   * Performs a Keycloak login and returns the Keycloak {@link KeycloakLoginResponseDTO} on success.
   *
   * @param userName the username
   * @param password the password
   * @return {@link KeycloakLoginResponseDTO}
   */
  public KeycloakLoginResponseDTO loginUser(final String userName, final String password) {
    // Keycloak stores decoded usernames; callers often pass the encoded form from MariaDB.
    var entity = loginRequest(usernameTranscoder.decodeUsername(userName), password);
    var url = identityClientConfig.getOpenIdConnectUrl(ENDPOINT_OPENID_CONNECT_LOGIN);

    try {
      return restTemplate.postForEntity(url, entity, KeycloakLoginResponseDTO.class).getBody();

    } catch (RestClientResponseException exception) {
      throw new BadRequestException(
          String.format(
              "Could not log in user %s into Keycloak: %s", userName, exception.getMessage()),
          exception);
    }
  }

  public boolean verifyIgnoringOtp(String username, String password) {
    var entity = loginRequest(usernameTranscoder.decodeUsername(username), password);
    var url = identityClientConfig.getOpenIdConnectUrl(ENDPOINT_OPENID_CONNECT_LOGIN);

    ResponseEntity<KeycloakLoginResponseDTO> loginResponse;
    try {
      loginResponse = restTemplate.postForEntity(url, entity, KeycloakLoginResponseDTO.class);
    } catch (HttpClientErrorException exception) {
      return exception.getStatusCode().equals(HttpStatus.BAD_REQUEST)
          && exception.getResponseBodyAsString().contains("Missing totp"); // but password correct
    }

    var responsePayload = loginResponse.getBody();
    if (nonNull(responsePayload) && nonNull(responsePayload.getRefreshToken())) {
      logoutUser(responsePayload.getRefreshToken());
    }

    return true;
  }

  /**
   * Performs a Keycloak logout. This only destroys the Keycloak session, the (offline) access token
   * will still be valid until expiration date/time ends.
   *
   * @param refreshToken the refreshToken
   * @return true if logout was successful
   */
  public boolean logoutUser(final String refreshToken) {
    MultiValueMap<String, String> map = new SensitiveKeycloakFormData();
    map.add(BODY_KEY_CLIENT_ID, keycloakClientId);
    map.add(BODY_KEY_GRANT_TYPE, KEYCLOAK_GRANT_TYPE_REFRESH_TOKEN);
    map.add(KEYCLOAK_GRANT_TYPE_REFRESH_TOKEN, refreshToken);

    var httpHeaders = new HttpHeaders();
    httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    httpHeaders.add("Authorization", "Bearer " + authenticatedUser.getAccessToken());
    HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, httpHeaders);

    var url = identityClientConfig.getOpenIdConnectUrl(ENDPOINT_OPENID_CONNECT_LOGOUT);
    try {
      var response = restTemplate.postForEntity(url, request, Void.class);
      return wasLogoutSuccessful(response);
    } catch (Exception ex) {
      log.error("Keycloak error: Could not log out user", ex);

      return false;
    }
  }

  private HttpEntity<MultiValueMap<String, String>> loginRequest(String userName, String password) {
    MultiValueMap<String, String> map = new SensitiveKeycloakFormData();
    map.add(BODY_KEY_USERNAME, userName);
    map.add(BODY_KEY_PASSWORD, password);
    map.add(BODY_KEY_CLIENT_ID, keycloakClientId);
    map.add(BODY_KEY_GRANT_TYPE, KEYCLOAK_GRANT_TYPE_PASSWORD);

    var httpHeaders = new HttpHeaders();
    httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

    return new HttpEntity<>(map, httpHeaders);
  }

  private boolean wasLogoutSuccessful(ResponseEntity<Void> responseEntity) {
    if (!responseEntity.getStatusCode().equals(HttpStatus.NO_CONTENT)) {
      log.error("Keycloak error: Could not log out user");

      return false;
    }
    return true;
  }

  private static final class SensitiveKeycloakFormData extends LinkedMultiValueMap<String, String> {

    private static final String REDACTED = "[REDACTED]";

    @Override
    public String toString() {
      var sanitized = new LinkedMultiValueMap<>(this);
      if (sanitized.containsKey(KEYCLOAK_GRANT_TYPE_REFRESH_TOKEN)) {
        sanitized.put(KEYCLOAK_GRANT_TYPE_REFRESH_TOKEN, Collections.singletonList(REDACTED));
      }
      if (sanitized.containsKey(BODY_KEY_PASSWORD)) {
        sanitized.put(BODY_KEY_PASSWORD, Collections.singletonList(REDACTED));
      }
      return sanitized.toString();
    }
  }
}
