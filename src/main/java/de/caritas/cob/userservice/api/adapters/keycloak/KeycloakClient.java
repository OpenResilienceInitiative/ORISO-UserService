package de.caritas.cob.userservice.api.adapters.keycloak;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import lombok.NonNull;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Component
public class KeycloakClient {

  private final RestTemplate restTemplate;

  private final Keycloak keycloak;

  private final KeycloakConfig keycloakConfig;

  public KeycloakClient(
      @Qualifier("keycloakRestTemplate") final RestTemplate restTemplate,
      final Keycloak keycloak,
      final KeycloakConfig keycloakConfig) {
    this.restTemplate = restTemplate;
    this.keycloak = keycloak;
    this.keycloakConfig = keycloakConfig;
  }

  public <T> ResponseEntity<T> get(String bearerToken, String url, Class<T> responseType)
      throws HttpClientErrorException {
    var httpHeaders = headersWithBearerToken(bearerToken);
    httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

    var entity = new HttpEntity<>(httpHeaders);

    return restTemplate.exchange(url, HttpMethod.GET, entity, responseType);
  }

  public <T> ResponseEntity<T> putForEntity(
      String bearerToken, String url, @Nullable Object request, Class<T> responseType)
      throws HttpClientErrorException {
    var httpHeaders = headersWithBearerToken(bearerToken);
    httpHeaders.setContentType(MediaType.APPLICATION_JSON);

    var entity = new HttpEntity<>(request, httpHeaders);

    return restTemplate.exchange(url, HttpMethod.PUT, entity, responseType);
  }

  public <T> ResponseEntity<T> postForEntity(
      String bearerToken, String url, @Nullable Object request, Class<T> responseType)
      throws HttpClientErrorException {
    var httpHeaders = headersWithBearerToken(bearerToken);
    httpHeaders.setContentType(MediaType.APPLICATION_JSON);

    var entity = new HttpEntity<>(request, httpHeaders);

    return restTemplate.postForEntity(url, entity, responseType);
  }

  public <T> ResponseEntity<T> delete(String bearerToken, String url, Class<T> responseType)
      throws HttpClientErrorException {
    var httpHeaders = headersWithBearerToken(bearerToken);
    httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

    var entity = new HttpEntity<>(httpHeaders);

    return restTemplate.exchange(url, HttpMethod.DELETE, entity, responseType);
  }

  public UsersResource getUsersResource() {
    return getRealmResource().users();
  }

  public RealmResource getRealmResource() {
    String realm = keycloakConfig.getRealm();
    return keycloak.realm(realm);
  }

  public String getBearerToken() {
    return keycloak.tokenManager().getAccessTokenString();
  }

  /**
   * Forces a fresh admin token grant, bypassing the cached access token.
   *
   * <p>The keycloak-admin-client only refreshes its cached token when the token's own {@code exp}
   * claim is close to expiry. It does not know that the underlying Keycloak session can be
   * invalidated server-side earlier (e.g. by session-idle timeout), so a cached token can look
   * valid locally while the admin REST API already rejects it with 401.
   */
  public void refreshAdminSession() {
    keycloak.tokenManager().grantToken();
  }

  @NonNull
  private HttpHeaders headersWithBearerToken(String bearerToken) {
    var httpHeaders = new HttpHeaders();
    httpHeaders.add("Authorization", "Bearer " + bearerToken);

    return httpHeaders;
  }
}
