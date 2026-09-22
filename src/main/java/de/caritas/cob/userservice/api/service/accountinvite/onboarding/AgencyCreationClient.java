package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.agencyadminserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.AdminAgencyControllerApi;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyDTO;
import de.caritas.cob.userservice.api.config.apiclient.AgencyAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-to-server creation of the Beratungsstelle an invite reserved but never created
 * (ORISO-Admin#998). Direct sibling of {@link TenantCreationClient}: the invitee has no account
 * while the onboarding wizard runs, so the call authenticates as the configured Keycloak technical
 * user. AgencyService admits that user to {@code createAgency} ONLY together with a {@code
 * reservedAgencyId} — the reservation an agency admin made when writing the invite is the
 * authorisation anchor, so this client can complete a creation an admin authorised and start none
 * of its own.
 *
 * <p>Reservation consumption is atomic on the AgencyService side: the agency row is written under
 * exactly the reserved ID and the reservation removed in one transaction; an ID that is no longer
 * reserved (consumed, released, taken) is rejected with 409.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgencyCreationClient {

  /**
   * Consulting types are a vestigial routing dimension in ORISO — topics carry the routing — but
   * the AgencyService contract still requires one. The wizard asks the invitee for topics, not for
   * a consulting type, so new Beratungsstellen are created under the platform default. Kept
   * configurable rather than hard-coded so an operator can move it without a code change.
   *
   * <p>The default is 1, the consulting type every ORISO installation ships (ORISO-Helm {@code
   * consulting-type-settings/c1.json}). It was 0 before, an ID no environment serves: AgencyService
   * validates the consulting type against ConsultingTypeService before it creates anything, so
   * every counsellor onboarding that had to create its reserved Beratungsstelle failed with an
   * unexplained 500 (ORISO-Admin#998).
   */
  @Value("${counsellor.onboarding.agency.default-consulting-type:1}")
  private int defaultConsultingType;

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull IdentityClientConfig identityClientConfig;

  private final @NonNull AgencyAdminServiceApiControllerFactory
      agencyAdminServiceApiControllerFactory;

  /**
   * Creates the agency under the invite's reserved ID and returns that ID.
   *
   * @throws ConflictException when AgencyService rejects the creation with 409 — the reserved ID
   *     was consumed, released or taken in the meantime (single-use link semantics upstream)
   */
  public Long createAgencyWithReservedId(
      Long reservedAgencyId, String name, Long tenantId, List<Long> topicIds) {
    var agency =
        new AgencyDTO()
            .reservedAgencyId(reservedAgencyId)
            .name(name)
            .tenantId(tenantId)
            .topicIds(topicIds == null ? List.of() : List.copyOf(topicIds))
            .teamAgency(false)
            .external(false)
            .consultingType(defaultConsultingType);
    try {
      var created = createControllerApi().createAgency(agency);
      if (created == null
          || created.getEmbedded() == null
          || created.getEmbedded().getId() == null) {
        throw new IllegalStateException("Agency creation returned no agency id");
      }
      return created.getEmbedded().getId();
    } catch (HttpClientErrorException.Conflict exception) {
      throw new ConflictException(
          "Agency creation conflicted — the reserved agency ID is no longer consumable");
    } catch (HttpServerErrorException exception) {
      // AgencyService is down or broken: a retryable downstream failure, not a fault of this
      // service. docs/api-error-contract.md reserves 424/502 for exactly this, so the invitee's
      // wizard can tell an outage apart from a request it must never repeat unchanged.
      logUpstreamFailure(reservedAgencyId, tenantId, exception);
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Agency creation is unavailable upstream");
    } catch (ResourceAccessException exception) {
      // No status at all — connection refused, timeout, DNS. Same class of failure as a 5xx.
      log.error(
          "Agency creation for reserved agency ID {} (tenant {}) could not reach AgencyService",
          reservedAgencyId,
          tenantId,
          exception);
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Agency creation is unavailable upstream");
    } catch (HttpStatusCodeException exception) {
      // A 4xx that is not the documented 409 means AgencyService rejected what this client sent
      // — a wrong consulting type, a malformed field. That is this service's own configuration or
      // request being wrong, so it stays a 500: repeating it unchanged cannot succeed.
      logUpstreamFailure(reservedAgencyId, tenantId, exception);
      throw new InternalServerErrorException(
          "Agency creation for the reserved ID was rejected upstream with status "
              + exception.getStatusCode().value());
    }
  }

  /**
   * Every rejection used to propagate raw, so the public onboarding endpoint answered a bare 500
   * and the reason existed only in AgencyService's log. The body is safe to record: this is a
   * server-to-server admin call, so it carries AgencyService's validation reason, never invitee
   * content.
   */
  private void logUpstreamFailure(
      Long reservedAgencyId, Long tenantId, HttpStatusCodeException exception) {
    log.error(
        "Agency creation for reserved agency ID {} (tenant {}, consulting type {}) failed:"
            + " AgencyService answered {} {}",
        reservedAgencyId,
        tenantId,
        defaultConsultingType,
        exception.getStatusCode().value(),
        exception.getResponseBodyAsString(),
        exception);
  }

  private AdminAgencyControllerApi createControllerApi() {
    var controllerApi = agencyAdminServiceApiControllerFactory.createControllerApi();
    addTechnicalUserHeaders(controllerApi.getApiClient());
    return controllerApi;
  }

  private void addTechnicalUserHeaders(ApiClient apiClient) {
    var techUser = identityClientConfig.getTechnicalUser();
    var identityLogin =
        identityAuthentication.login(techUser.getUsername(), techUser.getPassword());
    HttpHeaders headers =
        securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(identityLogin.accessToken());
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
