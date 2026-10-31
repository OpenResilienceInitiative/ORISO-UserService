package de.caritas.cob.userservice.api.service.agency;

import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * Reads AgencyService's deletion-log outbox (OBP 2.1 #130) as the RUNTIME task actor. There is no
 * legacy technical-user fallback: AgencyService only accepts the RUNTIME actor here, and the actor
 * credential never leaves this service.
 */
@Component
public class AgencyDeletionAuditOutboxClient {

  private static final String OUTBOX = "/internal/deletion-audit-outbox";

  private final RestTemplate restTemplate;
  private final SecurityHeaderSupplier securityHeaderSupplier;
  private final IdentityClientConfig identity;

  @Value("${agency.service.api.url}")
  private String agencyServiceBaseUrl;

  public AgencyDeletionAuditOutboxClient(
      RestTemplate restTemplate,
      SecurityHeaderSupplier securityHeaderSupplier,
      IdentityClientConfig identity) {
    this.restTemplate = restTemplate;
    this.securityHeaderSupplier = securityHeaderSupplier;
    this.identity = identity;
  }

  public List<PendingEntry> fetchPending(int limit) {
    var request = new HttpEntity<>(runtimeHeaders());
    try {
      var response =
          restTemplate.exchange(
              agencyServiceBaseUrl + OUTBOX + "?limit=" + limit,
              HttpMethod.GET,
              request,
              PendingEntry[].class);
      var body = response.getBody();
      return body == null ? List.of() : Arrays.asList(body);
    } catch (RuntimeException failure) {
      throw unavailable("read", failure);
    }
  }

  public void acknowledge(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    var httpHeaders = runtimeHeaders();
    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
    try {
      restTemplate.exchange(
          agencyServiceBaseUrl + OUTBOX + "/acknowledgements",
          HttpMethod.POST,
          new HttpEntity<>(Map.of("ids", ids), httpHeaders),
          Void.class);
    } catch (RuntimeException failure) {
      throw unavailable("acknowledgement", failure);
    }
  }

  private HttpHeaders runtimeHeaders() {
    if (!identity.isFiveActorMode()) {
      throw new IllegalStateException(
          "Agency deletion outbox needs TASK_IDENTITY_MODEL=FIVE_ACTORS");
    }
    return securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(
        identity.taskAccessToken(TaskPurpose.DELETION_AUDIT_PULL));
  }

  /** Neither tokens nor AgencyService response bodies cross this boundary. */
  private static IllegalStateException unavailable(String step, RuntimeException failure) {
    return new IllegalStateException(
        "Agency deletion outbox "
            + step
            + " failed: "
            + failure.getClass().getSimpleName()
            + (failure instanceof RestClientResponseException response
                ? " HTTP " + response.getStatusCode().value()
                : ""));
  }

  public record PendingEntry(
      Long id, Long agencyId, Long tenantId, String outcome, String createdAt) {}
}
