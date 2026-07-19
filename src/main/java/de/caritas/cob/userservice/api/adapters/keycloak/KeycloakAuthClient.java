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
 * Keycloak refactor, PR1 auth slice). Keeps login/logout/OTP-verify/session close behind a focused
 * collaborator so {@link KeycloakService} can stay the {@code IdentityClient} facade.
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

  /**
   * Verifies password AND active second factor by performing a full direct-grant login including
   * the {@code otp} form field (the vendored otp-config SPI validates it, ADR-013). Any obtained
   * session is logged out immediately — this is a verification, not a login.
   *
   * @return true only if Keycloak accepted password and OTP together
   */
  public boolean verifyWithOtp(String username, String password, String otp) {
    var entity = loginRequest(usernameTranscoder.decodeUsername(username), password);
    entity.getBody().add("otp", otp);
    var url = identityClientConfig.getOpenIdConnectUrl(ENDPOINT_OPENID_CONNECT_LOGIN);

    ResponseEntity<KeycloakLoginResponseDTO> loginResponse;
    try {
      loginResponse = restTemplate.postForEntity(url, entity, KeycloakLoginResponseDTO.class);
    } catch (RestClientResponseException exception) {
      return false;
    }

    var responsePayload = loginResponse.getBody();
    if (nonNull(responsePayload) && nonNull(responsePayload.getRefreshToken())) {
      logoutUser(responsePayload.getRefreshToken());
    }

    return loginResponse.getStatusCode().is2xxSuccessful();
  }

  public boolean verifyIgnoringOtp(String username, String password) {
    var entity = loginRequest(username, password);
    var url = identityClientConfig.getOpenIdConnectUrl(ENDPOINT_OPENID_CONNECT_LOGIN);

    ResponseEntity<KeycloakLoginResponseDTO> loginResponse;
    try {
      loginResponse = restTemplate.postForEntity(url, entity, KeycloakLoginResponseDTO.class);
    } catch (HttpClientErrorException exception) {
      return exception.getStatusCode().equals(HttpStatus.BAD_REQUEST)
          && isMissingTotpContract(exception.getResponseBodyAsString()); // but password correct
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

  /**
   * Closes the provided session.
   *
   * @param sessionId Keycloak session ID
   */
  public void closeSession(String sessionId) {
    keycloakClient.getRealmResource().deleteSession(sessionId, false);
  }

  /**
   * Strict contract check against the vendored otp-config SPI (ADR-013): the SPI answers a
   * password-correct-but-second-factor-absent direct grant with EXACTLY {@code error_description:
   * "Missing totp"}. Only that exact JSON field counts — a body merely containing the phrase must
   * never pass as password-verified. The SPI is vendored and version-pinned in ORISO-Helm, so this
   * string is under ORISO control; re-verify on SPI bumps.
   */
  private boolean isMissingTotpContract(String responseBody) {
    try {
      var node = ERROR_BODY_READER.readTree(responseBody);
      return node.hasNonNull("error_description")
          && "Missing totp".equals(node.get("error_description").asText());
    } catch (Exception e) {
      return false;
    }
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper ERROR_BODY_READER =
      new com.fasterxml.jackson.databind.ObjectMapper();

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
