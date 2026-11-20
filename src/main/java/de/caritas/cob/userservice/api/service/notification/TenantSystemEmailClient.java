package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.config.auth.RoutineTaskAuthentication;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
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
  public record MailContext(
      Boolean systemNotificationEmailsEnabled,
      String smtpMode,
      String emailThemeColor,
      boolean ownSmtpConfigured,
      boolean smtpConfigurationPresent) {}

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

  /**
   * Reads only the mail-routing data needed by the dispatcher. Five-actor callers must never use
   * the general tenant read endpoint because it deliberately rejects task tokens.
   */
  public MailContext readMailContext(long tenantId) {
    if (!identityConfig.isFiveActorMode()) {
      return fromLegacyTenant(readTenant(tenantId));
    }
    String url = systemEmailContextEndpoint(tenantId);
    Map<?, ?> response =
        restTemplate
            .exchange(url, HttpMethod.GET, new HttpEntity<Void>(technicalHeaders()), Map.class)
            .getBody();
    if (response == null) {
      throw new IllegalStateException("TenantService returned no system email context");
    }
    Map<?, ?> settings = map(response.get("settings"));
    Map<?, ?> smtp = settings == null ? null : map(settings.get("smtp"));
    return new MailContext(
        settings == null ? null : bool(settings.get("featureSystemNotificationEmailsEnabled")),
        settings == null ? null : string(settings.get("smtpMode")),
        smtp == null ? null : string(smtp.get("emailThemeColor")),
        smtp != null && Boolean.TRUE.equals(smtp.get("configured")),
        smtp != null && Boolean.TRUE.equals(smtp.get("enabled")));
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
            NotificationEmailDiagnostics.Stage.TENANT_SMTP,
            NotificationEmailDiagnostics.Reason.OWN_SMTP_DISABLED,
            "OWN tenant SMTP delivery is disabled");
      }
    } catch (HttpClientErrorException ex) {
      if (!isUnprocessable(ex)) throw ex;
      throw new TenantSystemEmailRouteService.ConfigurationException(
          NotificationEmailDiagnostics.Stage.TENANT_SMTP,
          NotificationEmailDiagnostics.Reason.OWN_SMTP_INVALID,
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
    String token =
        RoutineTaskAuthentication.accessToken(
            TaskPurpose.SYSTEM_EMAIL_DELIVERY, identityConfig, authentication);
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

  private String systemEmailContextEndpoint(long tenantId) {
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
    return tenantServiceApiUrl.replaceAll("/+$", "")
        + "/internal/tenants/"
        + tenantId
        + "/system-email-context";
  }

  private static MailContext fromLegacyTenant(Map<String, Object> tenant) {
    Map<?, ?> settings = map(tenant.get("settings"));
    Map<?, ?> smtp = settings == null ? null : map(settings.get("smtp"));
    boolean smtpPresent =
        smtp != null
            && java.util.stream.Stream.of(
                    "enabled", "host", "port", "secure", "username", "from", "passwordSet")
                .map(smtp::get)
                .anyMatch(TenantSystemEmailClient::configured);
    boolean smtpConfigured =
        smtp != null
            && Boolean.TRUE.equals(smtp.get("enabled"))
            && configured(smtp.get("host"))
            && validPort(smtp.get("port"))
            && smtp.get("secure") instanceof Boolean
            && configured(smtp.get("username"))
            && configured(smtp.get("from"))
            && Boolean.TRUE.equals(smtp.get("passwordSet"));
    return new MailContext(
        settings == null ? null : bool(settings.get("featureSystemNotificationEmailsEnabled")),
        settings == null ? null : string(settings.get("smtpMode")),
        smtp == null ? null : string(smtp.get("emailThemeColor")),
        smtpConfigured,
        smtpPresent);
  }

  private static Map<?, ?> map(Object value) {
    return value instanceof Map<?, ?> result ? result : null;
  }

  private static Boolean bool(Object value) {
    return value instanceof Boolean result ? result : null;
  }

  private static String string(Object value) {
    return value instanceof String result ? result : null;
  }

  private static boolean configured(Object value) {
    return value != null
        && !Boolean.FALSE.equals(value)
        && !(value instanceof String text && text.isBlank());
  }

  private static boolean validPort(Object value) {
    return value instanceof Number number && number.intValue() >= 1 && number.intValue() <= 65535;
  }
}
