package de.caritas.cob.userservice.api.admin.service.agency;

import static java.util.Objects.requireNonNull;

import de.caritas.cob.userservice.agencyadminserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.AdminAgencyControllerApi;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyAdminFullResponseDTO;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyAdminResponseDTO;
import de.caritas.cob.userservice.api.config.apiclient.AgencyAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/** Service to wrap admin api call for agencies. */
@Service
@RequiredArgsConstructor
public class AgencyAdminService {

  private static final int AGENCY_PAGE_SIZE = 100;

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull TenantHeaderSupplier tenantHeaderSupplier;

  private final @NonNull AgencyAdminServiceApiControllerFactory
      agencyAdminServiceApiControllerFactory;

  /**
   * Retrieves all agencies provided by agency service. Important hint: Depending on the amount of
   * existing agencies that call might need a few seconds to be performed.
   *
   * @return all existing agencies
   */
  public List<AgencyAdminResponseDTO> retrieveAllAgencies() {
    AdminAgencyControllerApi controllerApi =
        agencyAdminServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(controllerApi.getApiClient());
    return requireNonNull(
            controllerApi
                .searchAgencies(0, Integer.MAX_VALUE, null, null, null, null)
                .getEmbedded())
        .stream()
        .map(AgencyAdminFullResponseDTO::getEmbedded)
        .collect(Collectors.toList());
  }

  /**
   * Counts the agencies of a Träger that are not deleted, as the calling admin sees them.
   * AgencyService applies the tenant filter in the query and narrows it to the caller's scope, so
   * one row is enough to read the total.
   *
   * @param tenantId the Träger
   * @return the number of agencies of the Träger that are not soft-deleted
   */
  public long countActiveAgenciesOfTenant(Long tenantId) {
    AdminAgencyControllerApi controllerApi =
        agencyAdminServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(controllerApi.getApiClient());
    var result = controllerApi.searchAgencies(1, 1, null, null, true, tenantId);
    if (result == null || result.getTotal() == null) {
      throw new IllegalStateException(
          "AgencyService returned no agency total for tenant " + tenantId);
    }
    return result.getTotal();
  }

  /**
   * Ids of the agencies of a Träger that are not deleted yet, read page by page before any is
   * marked, so marking cannot shift the pages (ORISO-Admin#1525).
   *
   * @param tenantId the Träger
   * @return ids of its agencies that are not soft-deleted
   */
  public List<Long> findActiveAgencyIdsOfTenant(Long tenantId) {
    AdminAgencyControllerApi controllerApi =
        agencyAdminServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(controllerApi.getApiClient());
    var ids = new ArrayList<Long>();
    for (int page = 1; ; page++) {
      var result = controllerApi.searchAgencies(page, AGENCY_PAGE_SIZE, null, null, true, tenantId);
      var embedded = result == null ? null : result.getEmbedded();
      if (embedded == null || embedded.isEmpty()) {
        return ids;
      }
      embedded.stream()
          .map(AgencyAdminFullResponseDTO::getEmbedded)
          .filter(Objects::nonNull)
          .map(AgencyAdminResponseDTO::getId)
          .filter(Objects::nonNull)
          .forEach(ids::add);
      if (embedded.size() < AGENCY_PAGE_SIZE) {
        return ids;
      }
    }
  }

  /**
   * Marks one agency for deletion in AgencyService, as the calling admin. AgencyService refuses an
   * agency that still has counsellors, so the Träger deletion marks those first.
   *
   * @param agencyId the agency
   */
  public void markAgencyForDeletion(Long agencyId) {
    AdminAgencyControllerApi controllerApi =
        agencyAdminServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(controllerApi.getApiClient());
    controllerApi.deleteAgency(agencyId);
  }

  public void addDefaultHeaders(ApiClient apiClient) {
    HttpHeaders headers = this.securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
