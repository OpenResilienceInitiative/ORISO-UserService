package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
public class TenantSystemEmailDeliveryClient {
  public enum Purpose {
    EMAIL_ADDRESS_CHANGED,
    SUPERVISOR_ADDED,
    SUPERVISOR_REMOVED
  }

  private final RestTemplate restTemplate;
  private final IdentityAuthentication identityAuthentication;
  private final IdentityClientConfig identityClientConfig;
  private final SecurityHeaderSupplier headers;

  @Value("${tenant.service.api.url}")
  private String baseUrl;

  public boolean send(
      long tenantId, Purpose purpose, String recipient, OrisoEmailRenderer.RenderedEmail email) {
    if (tenantId <= 0) throw new IllegalArgumentException("Invalid delivery tenant");
    IdentityLogin login = null;
    try {
      var technical = identityClientConfig.getTechnicalUser();
      login = identityAuthentication.login(technical.getUsername(), technical.getPassword());
      if (login == null || login.accessToken() == null || login.accessToken().isBlank())
        throw new IllegalStateException("Service authentication unavailable");
      var request =
          new Delivery(
              purpose, recipient, email.subject(), email.html(), email.text(), UUID.randomUUID());
      var response =
          restTemplate.postForEntity(
              baseUrl.replaceAll("/+$", "")
                  + "/tenant/"
                  + tenantId
                  + "/internal/system-email-deliveries",
              new HttpEntity<>(request, headers.getKeycloakAndCsrfHttpHeaders(login.accessToken())),
              Void.class);
      if (response.getStatusCode() == HttpStatus.NO_CONTENT) return false;
      if (response.getStatusCode() != HttpStatus.OK)
        throw new IllegalStateException("Unexpected delivery status");
      return true;
    } catch (RuntimeException exception) {
      // No cause: downstream bodies/SMTP replies must never enter async logs.
      throw new IllegalStateException("Tenant system email delivery unconfirmed");
    } finally {
      if (login != null && login.refreshToken() != null && !login.refreshToken().isBlank()) {
        try {
          identityAuthentication.logout(login.refreshToken());
        } catch (RuntimeException ignored) {
          /* Never retry a possibly completed delivery. */
        }
      }
    }
  }

  record Delivery(
      Purpose purpose,
      String recipient,
      String subject,
      String html,
      String text,
      UUID correlationId) {
    @Override
    public String toString() {
      return "TenantSystemEmailDelivery[redacted]";
    }
  }
}
