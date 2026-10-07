package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/** Reads redacted tenant settings and delivers OWN mail without exposing its SMTP secret. */
@Component
public class TenantSystemEmailClient {
  private final RestTemplate restTemplate;
  private final IdentityAuthentication authentication;
  private final IdentityClientConfig identityConfig;
  private final SecurityHeaderSupplier headerSupplier;

  public TenantSystemEmailClient(
      RestTemplate restTemplate,
      IdentityAuthentication authentication,
      IdentityClientConfig identityConfig,
      @Qualifier("securityHeaderSupplier") SecurityHeaderSupplier headerSupplier) {
    this.restTemplate = restTemplate;
    this.authentication = authentication;
    this.identityConfig = identityConfig;
    this.headerSupplier = headerSupplier;
  }

  @Value("${tenant.service.api.url:}")
  private String tenantServiceApiUrl;

  @PostConstruct
  void validateTenantServiceUrl() {
    endpoint(1L, "");
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> readTenant(long tenantId) {
    String url =
        endpoint(tenantId, "")
            .replace(
                "/tenant/" + tenantId, "/internal/tenants/" + tenantId + "/system-email-context");
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
    deliver(tenantId, purpose, recipient, email, UUID.randomUUID());
  }

  public void deliver(
      long tenantId,
      String purpose,
      String recipient,
      OrisoEmailRenderer.RenderedEmail email,
      UUID correlationId) {
    HttpEntity<Map<String, Object>> request =
        deliveryRequest(technicalHeaders(), purpose, recipient, email, correlationId);
    try {
      var response =
          restTemplate.exchange(
              endpoint(tenantId, "/internal/system-email-deliveries"),
              HttpMethod.POST,
              request,
              Void.class);
      if (response.getStatusCode() == HttpStatus.NO_CONTENT) {
        throw new TenantSystemEmailRouteService.ConfigurationException(
            "OWN tenant SMTP delivery is disabled");
      }
    } catch (HttpClientErrorException ex) {
      if (!isUnprocessable(ex)) throw ex;
      throw new TenantSystemEmailRouteService.ConfigurationException(
          "OWN tenant SMTP configuration is invalid");
    }
  }

  /**
   * Same relay call under the strict invite contract: callers holding a deduplication claim must
   * know whether the tenant's SMTP server may have accepted the mail.
   *
   * @throws SmtpSendException for every outcome other than an accepted delivery
   */
  public void deliverStrict(
      long tenantId,
      String purpose,
      String recipient,
      OrisoEmailRenderer.RenderedEmail email,
      UUID correlationId) {
    HttpEntity<Map<String, Object>> request;
    String url;
    try {
      url = endpoint(tenantId, "/internal/system-email-deliveries");
      request = deliveryRequest(technicalHeaders(), purpose, recipient, email, correlationId);
    } catch (RuntimeException beforeRelay) {
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_SETTINGS_UNAVAILABLE,
          SmtpSendException.DeliveryDisposition.CONFIRMED_NOT_SENT,
          "Tenant mail relay could not be called",
          beforeRelay);
    }
    HttpStatusCode status;
    try {
      status = restTemplate.exchange(url, HttpMethod.POST, request, Void.class).getStatusCode();
    } catch (HttpClientErrorException rejected) {
      // TenantService validates before it opens an SMTP connection.
      throw isUnprocessable(rejected)
          ? notSent(SmtpSendException.Category.SMTP_DISABLED_OR_INCOMPLETE, "invalid", rejected)
          : notSent(SmtpSendException.Category.SMTP_SETTINGS_UNAVAILABLE, "rejected", rejected);
    } catch (RuntimeException uncertain) {
      // 502 means TenantService saw SMTP fail mid-transfer; a timeout can hide a sent mail.
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_TRANSPORT_FAILED,
          SmtpSendException.DeliveryDisposition.DELIVERY_UNCERTAIN,
          "Tenant mail relay did not confirm the delivery",
          uncertain);
    }
    if (status == HttpStatus.NO_CONTENT) {
      throw notSent(SmtpSendException.Category.SMTP_DISABLED_OR_INCOMPLETE, "disabled", null);
    }
    if (!status.is2xxSuccessful()) {
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_TRANSPORT_FAILED,
          SmtpSendException.DeliveryDisposition.DELIVERY_UNCERTAIN,
          "Tenant mail relay answered " + status.value());
    }
  }

  // Spring 7 maps a real 422 to UnprocessableContent, not the deprecated UnprocessableEntity.
  private static boolean isUnprocessable(HttpClientErrorException exception) {
    return exception.getStatusCode().value() == 422;
  }

  private static SmtpSendException notSent(
      SmtpSendException.Category category, String reason, Throwable cause) {
    return new SmtpSendException(
        category,
        SmtpSendException.DeliveryDisposition.CONFIRMED_NOT_SENT,
        "OWN tenant SMTP delivery " + reason,
        cause);
  }

  private static HttpEntity<Map<String, Object>> deliveryRequest(
      HttpHeaders headers,
      String purpose,
      String recipient,
      OrisoEmailRenderer.RenderedEmail email,
      UUID correlationId) {
    headers.setContentType(MediaType.APPLICATION_JSON);
    Map<String, Object> body =
        Map.of(
            "purpose", purpose,
            "recipient", recipient,
            "subject", email.subject(),
            "html", email.html(),
            "text", email.text(),
            "correlationId", correlationId.toString());
    return new HttpEntity<>(body, headers);
  }

  private HttpHeaders technicalHeaders() {
    var account =
        identityConfig.getTaskIdentity(
            de.caritas.cob.userservice.api.config.auth.TaskIdentity.NOTIFICATION_DISPATCH);
    if (account == null || account.getClientId() == null || account.getClientSecret() == null) {
      throw new IllegalStateException("Identity technical account is not configured");
    }
    String token = authentication.loginTask(account).accessToken();
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
