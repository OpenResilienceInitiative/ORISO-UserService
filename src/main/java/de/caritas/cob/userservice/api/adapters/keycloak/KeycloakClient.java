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

  private final KeycloakConfig keycloakConfig;

  @org.springframework.beans.factory.annotation.Autowired
  public KeycloakClient(
      @Qualifier("keycloakRestTemplate") RestTemplate restTemplate, KeycloakConfig keycloakConfig) {
    this.restTemplate = restTemplate;
    this.keycloakConfig = keycloakConfig;
  }

  /**
   * Retained only for source compatibility; native administrator clients are never held or used.
   */
  @Deprecated
  public KeycloakClient(
      RestTemplate restTemplate, Keycloak ignored, KeycloakConfig keycloakConfig) {
    this(restTemplate, keycloakConfig);
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

  @Deprecated
  public UsersResource getUsersResource() {
    throw retired();
  }

  @Deprecated
  public RealmResource getRealmResource() {
    throw retired();
  }

  @Deprecated
  public String getBearerToken() {
    throw retired();
  }

  @Deprecated
  public void refreshAdminSession() {
    throw retired();
  }

  private org.springframework.security.access.AccessDeniedException retired() {
    return new org.springframework.security.access.AccessDeniedException(
        "Native administrator transport has been retired; use a bounded task command");
  }

  @NonNull
  private HttpHeaders headersWithBearerToken(String bearerToken) {
    var httpHeaders = new HttpHeaders();
    httpHeaders.add("Authorization", "Bearer " + bearerToken);

    return httpHeaders;
  }
}
