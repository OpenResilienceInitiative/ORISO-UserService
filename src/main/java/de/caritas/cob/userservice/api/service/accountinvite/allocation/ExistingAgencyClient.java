package de.caritas.cob.userservice.api.service.accountinvite.allocation;

import de.caritas.cob.userservice.agencyadminserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.AdminAgencyControllerApi;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.TopicDTO;
import de.caritas.cob.userservice.api.config.apiclient.AgencyAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Looks up an agency that an invite wants to bind to WITHOUT reserving its ID ({@link
 * IdAllocationMode#EXISTING}, ORISO-Admin#1026). Uses AgencyService's admin detail endpoint with
 * the calling admin's token, because only that view carries the {@code deleteDate}: the public
 * {@code /agencies/{ids}} lookup also returns soft-deleted agencies without saying so.
 */
@Service
@RequiredArgsConstructor
public class ExistingAgencyClient {

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull TenantHeaderSupplier tenantHeaderSupplier;
  private final @NonNull AgencyAdminServiceApiControllerFactory
      agencyAdminServiceApiControllerFactory;

  /** The facts an EXISTING invite is validated against. */
  public record ExistingAgency(Long id, Long tenantId, boolean deleted, List<Long> topicIds) {}

  /**
   * @return the agency, or empty when AgencyService does not know it (404) — for a tenant-bound
   *     caller that includes an agency of another tenant, which the tenant-aware lookup hides
   */
  public Optional<ExistingAgency> find(long agencyId) {
    try {
      var response = createControllerApi().getAgency(agencyId);
      var agency = response == null ? null : response.getEmbedded();
      if (agency == null) {
        return Optional.empty();
      }
      List<Long> topicIds =
          agency.getTopics() == null
              ? List.of()
              : agency.getTopics().stream().map(TopicDTO::getId).filter(Objects::nonNull).toList();
      return Optional.of(
          new ExistingAgency(
              agency.getId(), agency.getTenantId(), isDeleted(agency.getDeleteDate()), topicIds));
    } catch (HttpClientErrorException.NotFound exception) {
      return Optional.empty();
    }
  }

  /**
   * AgencyService serialises the delete date with {@code String.valueOf}, so a live agency arrives
   * as the literal {@code "null"}, not as JSON null.
   */
  static boolean isDeleted(String deleteDate) {
    return deleteDate != null && !deleteDate.isBlank() && !"null".equalsIgnoreCase(deleteDate);
  }

  private AdminAgencyControllerApi createControllerApi() {
    var controllerApi = agencyAdminServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(controllerApi.getApiClient());
    return controllerApi;
  }

  private void addDefaultHeaders(ApiClient apiClient) {
    HttpHeaders headers = this.securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
