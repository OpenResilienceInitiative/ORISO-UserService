package de.caritas.cob.userservice.api.service.agency;

import de.caritas.cob.userservice.agencyserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyResponseDTO;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownContentTypeException;

/**
 * Reads the <em>effective</em> feature settings of one Beratungsstelle (agency) as the
 * AgencyService serves them on {@code GET /agencies/{agencyIds}} (US#1171, ADR-013).
 *
 * <p>The public format booleans already include canonical requirements and agency choices. They
 * must not be re-merged with a cached or raw tenant flag by consumers.
 *
 * <p>Deliberately <b>not cached</b>: an admin who switches a format off expects the very next
 * create call to be refused, and gate checks are rare compared to the cached agency reads in {@link
 * AgencyService}. The cached {@link AgencyService#getAgency(Long)} cannot be reused here because
 * its {@code AgencyDTO} mapping drops the settings block.
 *
 * <p>Failure contract: an unknown agency, a 404, or an agency without a settings block yields
 * {@link Optional#empty()}. Foreign/ambiguous projections and authorization refusals fail closed.
 * Availability failures (timeout, 5xx, ...) propagate as {@link
 * org.springframework.web.client.RestClientException}; the caller falls back to the tenant value.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EffectiveAgencySettingsLookup {

  private final @NonNull AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;
  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull TenantHeaderSupplier tenantHeaderSupplier;

  /**
   * Fresh, uncached effective settings of the given agency.
   *
   * @param agencyId the Beratungsstelle to look up
   * @param expectedTenantId the established tenant; a present projection tenant must match
   * @return the settings block served by the AgencyService, empty if the agency is unknown or
   *     carries no settings
   */
  public Optional<Settings> findEffectiveSettings(Long agencyId, Long expectedTenantId) {
    if (agencyId == null) {
      return Optional.empty();
    }
    var agencyControllerApi = agencyServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(agencyControllerApi.getApiClient());
    try {
      List<AgencyResponseDTO> agencies = agencyControllerApi.getAgenciesByIds(List.of(agencyId));
      if (agencies == null || agencies.isEmpty()) return Optional.empty();
      var matching =
          agencies.stream()
              .filter(Objects::nonNull)
              .filter(agency -> agencyId.equals(agency.getId()))
              .toList();
      if (matching.size() != 1) {
        throw new ForbiddenException("AgencyService returned no unique matching agency");
      }
      var agency = matching.getFirst();
      if (expectedTenantId != null
          && agency.getTenantId() != null
          && !expectedTenantId.equals(agency.getTenantId())) {
        throw new ForbiddenException("AgencyService returned an agency of another tenant");
      }
      return Optional.ofNullable(agency.getSettings());
    } catch (HttpClientErrorException httpClientErrorException) {
      if (HttpStatus.NOT_FOUND.equals(httpClientErrorException.getStatusCode())) {
        log.warn(
            "AgencyService returned 404 for agency {} while reading effective settings.", agencyId);
        return Optional.empty();
      }
      if (HttpStatus.FORBIDDEN.equals(httpClientErrorException.getStatusCode())
          || HttpStatus.UNAUTHORIZED.equals(httpClientErrorException.getStatusCode())) {
        throw new ForbiddenException("AgencyService refused access to the requested agency");
      }
      throw httpClientErrorException;
    } catch (RestClientException failure) {
      // A successful HTTP response with unreadable authority is not an outage. Do not leak
      // upstream content through the exception or let the gate fall back to tenant-on.
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof HttpMessageConversionException
            || cause instanceof UnknownContentTypeException) {
          throw new ServiceUnavailableException("Agency policy projection is unreadable");
        }
      }
      throw failure;
    }
  }

  private void addDefaultHeaders(ApiClient apiClient) {
    // Same header policy as AgencyService: forward the user token only when one exists, always
    // forward the tenant header.
    var headers = securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
