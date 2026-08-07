package de.caritas.cob.userservice.api.service.accountinvite.allocation;

import de.caritas.cob.userservice.agencyadminserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.AdminAgencyControllerApi;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyIdReservationRequestDTO;
import de.caritas.cob.userservice.api.config.apiclient.AgencyAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Client-boundary wrapper for the authoritative agency-ID allocation endpoints of AgencyService
 * (TEN-INV-U2, {@code /agencyadmin/agencyids/**}).
 *
 * <p>Per the U2 decision (ORISO-AgencyService#214) agency IDs live in AgencyService's own ID space;
 * a supplied tenantId is validated by AgencyService but never reserved there.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgencyIdAllocationClient {

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull TenantHeaderSupplier tenantHeaderSupplier;

  private final @NonNull AgencyAdminServiceApiControllerFactory
      agencyAdminServiceApiControllerFactory;

  /**
   * Reserves an agency ID. {@code requestedAgencyId == null} is AUTO mode: AgencyService assigns
   * the smallest currently free ID atomically.
   *
   * @throws ConflictException when the requested ID is already assigned or reserved (upstream 409)
   */
  public long reserve(Long requestedAgencyId, Long tenantId) {
    var request =
        new AgencyIdReservationRequestDTO().agencyId(requestedAgencyId).tenantId(tenantId);
    try {
      return createControllerApi().reserveAgencyId(request).getAgencyId();
    } catch (HttpClientErrorException.Conflict exception) {
      throw new ConflictException(
          "agencyId " + requestedAgencyId + " is already assigned or reserved");
    }
  }

  /** Returns the authoritative allocation status of one agency ID. */
  public IdAllocationStatus getAvailability(long agencyId) {
    var availability = createControllerApi().getAgencyIdAvailability(agencyId);
    return IdAllocationStatus.valueOf(availability.getStatus().getValue());
  }

  /**
   * Best-effort compensation: releases an unconsumed reservation so the ID becomes assignable
   * again. Never throws — a failed release must not mask the original creation failure; the
   * upstream reservation ledger stays the single source of truth for manual cleanup.
   */
  public void release(long agencyId) {
    try {
      createControllerApi().releaseAgencyIdReservation(agencyId);
    } catch (HttpClientErrorException.NotFound exception) {
      log.info("Agency ID reservation {} was already released", agencyId);
    } catch (RestClientException exception) {
      log.error(
          "Failed to release agency ID reservation {} — possible orphaned reservation in"
              + " AgencyService",
          agencyId,
          exception);
    }
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
