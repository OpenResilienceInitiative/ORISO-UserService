package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/** Reads redacted tenant settings and delivers OWN mail without exposing its SMTP secret. */
@Component
@RequiredArgsConstructor
public class TenantSystemEmailClient {
  private final @NonNull RestTemplate restTemplate;
  private final @NonNull IdentityAuthentication authentication;
  private final @NonNull IdentityClientConfig identityConfig;
  private final @NonNull SecurityHeaderSupplier headerSupplier;

  @Value("${tenant.service.api.url:}")
  private String tenantServiceApiUrl;

  @PostConstruct
  void validateTenantServiceUrl() {
    endpoint(1L, "");
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> readTenant(long tenantId) {
    String url = endpoint(tenantId, "");
    Map<?, ?> response =
        restTemplate
            .exchange(url, HttpMethod.GET, new HttpEntity<Void>(technicalHeaders()), Map.class)
            .getBody();
    if (response == null) {
      throw new IllegalStateException("TenantService returned no tenant settings");
    }
    return (Map<String, Object>) response;
  }

  public void deliver(
      long tenantId, String purpose, String recipient, OrisoEmailRenderer.RenderedEmail email) {
    HttpHeaders headers = technicalHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    Map<String, Object> request =
        Map.of(
            "purpose", purpose,
            "recipient", recipient,
            "subject", email.subject(),
            "html", email.html(),
            "text", email.text(),
            "correlationId", UUID.randomUUID().toString());
    try {
      restTemplate.exchange(
          endpoint(tenantId, "/internal/system-email-deliveries"),
          HttpMethod.POST,
          new HttpEntity<>(request, headers),
          Void.class);
    } catch (HttpClientErrorException.UnprocessableEntity ex) {
      throw new TenantSystemEmailRouteService.ConfigurationException(
          "OWN tenant SMTP configuration is invalid");
    }
  }

  private HttpHeaders technicalHeaders() {
    var account = identityConfig.getTechnicalUser();
    if (account == null || account.getUsername() == null || account.getPassword() == null) {
      throw new IllegalStateException("Identity technical account is not configured");
    }
    String token = authentication.login(account.getUsername(), account.getPassword()).accessToken();
    return headerSupplier.getKeycloakAndCsrfHttpHeaders(token);
  }

  private String endpoint(long tenantId, String suffix) {
    if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
    if (tenantServiceApiUrl == null || tenantServiceApiUrl.isBlank()) {
      throw new IllegalStateException(
          "tenant.service.api.url (TENANT_SERVICE_API_URL) is required");
    }
    URI base = URI.create(tenantServiceApiUrl.trim());
    if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
        || base.getHost() == null) {
      throw new IllegalStateException("tenant.service.api.url (TENANT_SERVICE_API_URL) is invalid");
    }
    return tenantServiceApiUrl.replaceAll("/+$", "") + "/tenant/" + tenantId + suffix;
  }
}
