package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.RoutineTaskAuthentication;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.notification.DpaAdminNoticeOutboxConsumer.Event;
import de.caritas.cob.userservice.api.service.notification.DpaAdminNoticeOutboxConsumer.VerifiedSignature;
import de.caritas.cob.userservice.tenantadminservice.generated.ApiClient;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaSignatureDTO;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Authoritative read-back of a tenant's DPA signature audit list from TenantService
 * (ORISO-UserService#1005). The signed-notice trigger arriving from TenantService is an
 * UNAUTHENTICATED hint; every fact that ends up in a notice mail is read through THIS authenticated
 * technical-user call instead — a spoofed hint can therefore never fabricate notice content. Same
 * technical-user pattern as {@code TenantCreationClient}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantDpaSignatureReadClient {

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull IdentityClientConfig identityClientConfig;

  private final @NonNull TenantAdminServiceApiControllerFactory
      tenantAdminServiceApiControllerFactory;
  private final @NonNull RestTemplate restTemplate;

  @Value("${tenant.service.api.url:}")
  private String tenantServiceApiUrl;

  @Value("${tenant.dpa.admin-notice-outbox.read-enabled:false}")
  private boolean outboxReadEnabled;

  /** The tenant's DPA signature audit rows; empty when the read fails (notice stays unsent). */
  public List<DpaSignatureDTO> readSignatures(Long tenantId) {
    try {
      return createControllerApi().getDataProcessingAgreementSignatures(tenantId);
    } catch (RuntimeException exception) {
      // Deliberately every RuntimeException: the technical-user login inside
      // addTechnicalUserHeaders wraps HTTP failures in BadRequestException, which is NOT a
      // RestClientException and would otherwise escape and turn the anonymous hint endpoint's
      // documented 202 into a 400. Nothing here may surface to that caller.
      //
      // A failed read means no notice is ever sent and no ledger row is claimed, so this line is
      // the only trace of a silently skipped notice — it carries the exception, not just its
      // class name, or the status and body are lost with it.
      log.warn(
          "Could not read DPA signatures for tenant {} from TenantService", tenantId, exception);
      return List.of();
    }
  }

  PendingPage readPending(long afterId, int limit) {
    if (afterId < 0) {
      throw new IllegalArgumentException("Invalid DPA outbox cursor");
    }
    var request = outboxRequest();
    int boundedLimit = Math.clamp(limit, 1, 100);
    var url =
        outboxUri()
            .queryParam("afterId", afterId)
            .queryParam("limit", boundedLimit)
            .build()
            .toUri();
    PendingPage page;
    try {
      page = restTemplate.exchange(url, HttpMethod.GET, request, PendingPage.class).getBody();
    } catch (RestClientException failure) {
      throw new OutboxRequestFailure(OutboxOperation.PAGE, failure);
    }
    if (page == null || page.events() == null || page.events().size() > boundedLimit) {
      throw new IllegalStateException("Invalid TenantService DPA outbox page");
    }
    long examined = afterId;
    for (var event : page.events()) {
      if (!valid(event) || event.id() <= examined) {
        throw new IllegalStateException("Invalid TenantService DPA outbox event order");
      }
      examined = event.id();
    }
    if (page.nextAfterId() != null
        && (page.nextAfterId() <= afterId || page.nextAfterId() < examined)) {
      throw new IllegalStateException("Invalid TenantService DPA outbox continuation");
    }
    return new PendingPage(List.copyOf(page.events()), page.nextAfterId());
  }

  Optional<VerifiedSignature> readExact(Event event) {
    if (!valid(event)) {
      return Optional.empty();
    }
    var request = outboxRequest();
    var url = outboxUri().pathSegment(Long.toString(event.id())).build().toUri();
    ExactSignature exact;
    try {
      exact = restTemplate.exchange(url, HttpMethod.GET, request, ExactSignature.class).getBody();
    } catch (HttpClientErrorException.NotFound unavailable) {
      return Optional.empty();
    } catch (RestClientException failure) {
      throw new OutboxRequestFailure(OutboxOperation.SIGNATURE, failure);
    }
    if (exact == null
        || !event.equals(new Event(exact.id(), exact.tenantId(), exact.signedAtEpochMillis()))
        || exact.dpaVersion() == null
        || exact.dpaVersion().isBlank()
        || exact.signedAt() == null
        || exact.signedAt().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        new VerifiedSignature(
            event,
            new DpaSignatureDTO()
                .tenantId(exact.tenantId())
                .status(DpaSignedNoticeService.STATUS_SIGNED)
                .source(DpaSignedNoticeService.SOURCE_FORWARDED_EXTERNAL)
                .dpaVersion(exact.dpaVersion())
                .signedAt(exact.signedAt())
                .forwardedByUserId(exact.forwardedByUserId())
                .signerName(exact.signerName())
                .signerPosition(exact.signerPosition())));
  }

  /** Called by the exact consumer after the durable receipt has committed. */
  void acknowledge(Event event) {
    if (!valid(event)) {
      throw new IllegalArgumentException("Invalid DPA outbox event");
    }
    var headers = outboxRequest().getHeaders();
    var url =
        outboxUri().pathSegment(Long.toString(event.id()), "acknowledgements").build().toUri();
    var request = new HttpEntity<>(new Acknowledgement(event.signedAtEpochMillis()), headers);
    try {
      if (restTemplate.exchange(url, HttpMethod.POST, request, Void.class).getStatusCode()
          == HttpStatus.NO_CONTENT) {
        return;
      }
    } catch (RestClientException failure) {
      throw new OutboxRequestFailure(OutboxOperation.ACKNOWLEDGEMENT, failure);
    }
    throw new IllegalStateException("TenantService did not confirm the exact DPA acknowledgement");
  }

  boolean isOutboxEnabled() {
    return outboxReadEnabled && identityClientConfig.isFiveActorMode();
  }

  private HttpEntity<Void> outboxRequest() {
    if (!isOutboxEnabled()) {
      throw new IllegalStateException("Signed DPA outbox transport is disabled");
    }
    String token = identityClientConfig.taskAccessToken(TaskPurpose.NOTIFICATION_DISPATCH);
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("DPA outbox task authentication is unavailable");
    }
    return new HttpEntity<>(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(token));
  }

  private UriComponentsBuilder outboxUri() {
    return UriComponentsBuilder.fromUriString(tenantServiceApiUrl)
        .pathSegment("internal", "dpa-admin-notice-outbox");
  }

  private static boolean valid(Event event) {
    return event != null
        && event.id() > 0
        && event.tenantId() > 0
        && event.signedAtEpochMillis() > 0;
  }

  enum OutboxOperation {
    PAGE("TenantService DPA outbox page"),
    SIGNATURE("TenantService exact DPA signature"),
    ACKNOWLEDGEMENT("TenantService DPA acknowledgement");

    private final String label;

    OutboxOperation(String label) {
      this.label = label;
    }
  }

  /** A safe diagnostic for the scheduler. The original exception may retain a URL or body. */
  static final class OutboxRequestFailure extends IllegalStateException {
    OutboxRequestFailure(OutboxOperation operation, RestClientException failure) {
      super(operation.label + " is unavailable (" + category(failure) + ")");
    }

    private static String category(RestClientException failure) {
      return switch (failure) {
        case RestClientResponseException response -> "HTTP " + response.getStatusCode().value();
        case ResourceAccessException ignored -> "transport failure";
        default -> "client failure";
      };
    }
  }

  record PendingPage(List<Event> events, Long nextAfterId) {}

  private record Acknowledgement(long signedAtEpochMillis) {}

  private record ExactSignature(
      long id,
      long tenantId,
      long signedAtEpochMillis,
      String dpaVersion,
      String signedAt,
      String forwardedByUserId,
      String signerName,
      String signerPosition) {}

  private TenantControllerApi createControllerApi() {
    var controllerApi = tenantAdminServiceApiControllerFactory.createControllerApi();
    addTechnicalUserHeaders(controllerApi.getApiClient());
    return controllerApi;
  }

  private void addTechnicalUserHeaders(ApiClient apiClient) {
    String token =
        RoutineTaskAuthentication.accessToken(
            TaskPurpose.NOTIFICATION_DISPATCH, identityClientConfig, identityAuthentication);
    HttpHeaders headers = securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(token);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
