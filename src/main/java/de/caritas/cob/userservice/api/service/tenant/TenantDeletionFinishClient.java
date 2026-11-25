package de.caritas.cob.userservice.api.service.tenant;

import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * Finishes Träger deletions in TenantService as the RUNTIME task actor with purpose TENANT_DELETION
 * (ORISO-Admin#1525). There is no legacy technical-user fallback: TenantService only lets that
 * actor list and remove Träger in deletion, and the credential never leaves this service.
 */
@Component
public class TenantDeletionFinishClient {

  private final RestTemplate restTemplate;
  private final SecurityHeaderSupplier securityHeaderSupplier;
  private final IdentityClientConfig identity;

  @Value("${tenant.service.api.url:}")
  private String tenantServiceApiUrl;

  public TenantDeletionFinishClient(
      RestTemplate restTemplate,
      SecurityHeaderSupplier securityHeaderSupplier,
      IdentityClientConfig identity) {
    this.restTemplate = restTemplate;
    this.securityHeaderSupplier = securityHeaderSupplier;
    this.identity = identity;
  }

  /** Ids of the Träger marked for deletion. */
  public List<Long> findTenantsMarkedForDeletion() {
    try {
      var body =
          restTemplate
              .exchange(
                  endpoint("/tenant/deletions"),
                  HttpMethod.GET,
                  new HttpEntity<>(runtimeHeaders()),
                  Long[].class)
              .getBody();
      return body == null
          ? List.of()
          : Arrays.stream(body).filter(Objects::nonNull).filter(id -> id > 0).toList();
    } catch (RuntimeException failure) {
      throw unavailable("list", failure);
    }
  }

  /**
   * Removes a Träger marked for deletion. A Träger TenantService no longer knows counts as removed,
   * so a run whose answer was lost can be repeated.
   *
   * @throws IllegalStateException when TenantService refuses (e.g. the Träger is not marked) or
   *     cannot be reached; the Träger stays for the next run
   */
  public void removeTenant(long tenantId) {
    if (tenantId <= 0) {
      throw new IllegalArgumentException("tenantId must be positive");
    }
    try {
      restTemplate.exchange(
          endpoint("/tenant/" + tenantId + "/deletion"),
          HttpMethod.DELETE,
          new HttpEntity<>(runtimeHeaders()),
          Void.class);
    } catch (RestClientResponseException response) {
      if (response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
        return;
      }
      throw unavailable("removal", response);
    } catch (RuntimeException failure) {
      throw unavailable("removal", failure);
    }
  }

  private HttpHeaders runtimeHeaders() {
    if (!identity.isFiveActorMode()) {
      throw new IllegalStateException(
          "Finishing Träger deletions needs TASK_IDENTITY_MODEL=FIVE_ACTORS");
    }
    return securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(
        identity.taskAccessToken(TaskPurpose.TENANT_DELETION));
  }

  private String endpoint(String path) {
    if (tenantServiceApiUrl == null || tenantServiceApiUrl.isBlank()) {
      throw new IllegalStateException(
          "tenant.service.api.url (TENANT_SERVICE_API_URL) is required");
    }
    URI base = URI.create(tenantServiceApiUrl.trim());
    if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
        || base.getHost() == null) {
      throw new IllegalStateException("tenant.service.api.url (TENANT_SERVICE_API_URL) is invalid");
    }
    return tenantServiceApiUrl.trim().replaceAll("/+$", "") + path;
  }

  /** Neither tokens nor TenantService response bodies cross this boundary. */
  private static IllegalStateException unavailable(String step, RuntimeException failure) {
    return new IllegalStateException(
        "Träger deletion "
            + step
            + " failed: "
            + failure.getClass().getSimpleName()
            + (failure instanceof RestClientResponseException response
                ? " HTTP " + response.getStatusCode().value()
                : ""));
  }
}
