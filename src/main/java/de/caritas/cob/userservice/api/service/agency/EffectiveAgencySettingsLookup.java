package de.caritas.cob.userservice.api.service.agency;

import de.caritas.cob.userservice.agencyserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyResponseDTO;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Reads the <em>effective</em> feature settings of one Beratungsstelle (agency) as the
 * AgencyService serves them on {@code GET /agencies/{agencyIds}} (US#1171, ADR-013).
 *
 * <p>"Effective" means Traeger AND Beratungsstelle already combined by the AgencyService
 * (ORISO-AgencyService#293): a Traeger "off" always wins, a Beratungsstelle may only restrict, and
 * {@code null} means "no restriction on this level". Feature gates that today read only the Traeger
 * value from the TenantService should read the requested agency through this lookup instead; the
 * first adopter is {@link de.caritas.cob.userservice.api.service.chat.GroupChatFeatureGate}.
 *
 * <p>Deliberately <b>not cached</b>: an admin who switches a format off expects the very next
 * create call to be refused, and gate checks are rare compared to the cached agency reads in {@link
 * AgencyService}. The cached {@link AgencyService#getAgency(Long)} cannot be reused here because
 * its {@code AgencyDTO} mapping drops the settings block.
 *
 * <p>Failure contract: an unknown agency, a 404, or an agency without a settings block yields
 * {@link Optional#empty()}. Every other client failure (timeout, 5xx, ...) propagates as the {@link
 * org.springframework.web.client.RestClientException} thrown by the client, so that the caller
 * decides how to degrade (the group-chat gate falls back to the Traeger value).
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
   * @return the settings block served by the AgencyService, empty if the agency is unknown or
   *     carries no settings
   */
  public Optional<Settings> findEffectiveSettings(Long agencyId) {
    if (agencyId == null) {
      return Optional.empty();
    }
    var agencyControllerApi = agencyServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(agencyControllerApi.getApiClient());
    try {
      List<AgencyResponseDTO> agencies = agencyControllerApi.getAgenciesByIds(List.of(agencyId));
      return agencies == null
          ? Optional.empty()
          : agencies.stream()
              .filter(Objects::nonNull)
              .findFirst()
              .map(AgencyResponseDTO::getSettings);
    } catch (HttpClientErrorException httpClientErrorException) {
      if (HttpStatus.NOT_FOUND.equals(httpClientErrorException.getStatusCode())) {
        log.warn(
            "AgencyService returned 404 for agency {} while reading effective settings.", agencyId);
        return Optional.empty();
      }
      throw httpClientErrorException;
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
