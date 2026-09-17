package de.caritas.cob.userservice.api.service.accountinvite.allocation;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.ApiClient;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantIdReservationRequestDTO;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Client-boundary wrapper for the authoritative tenant-ID allocation endpoints of TenantService
 * (TEN-INV-U1, {@code /tenantadmin/tenant-ids/**}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantIdAllocationClient {

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;

  private final @NonNull TenantAdminServiceApiControllerFactory
      tenantAdminServiceApiControllerFactory;

  /**
   * Reserves a tenant ID. {@code requestedTenantId == null} is AUTO mode: TenantService assigns the
   * smallest currently free ID atomically.
   *
   * @throws ConflictException when the requested ID is already assigned or reserved (upstream 409)
   */
  public TenantIdReservation reserve(Long requestedTenantId) {
    var request = new TenantIdReservationRequestDTO().tenantId(requestedTenantId);
    try {
      var reservation = createControllerApi().reserveTenantId(request);
      return new TenantIdReservation(reservation.getTenantId(), reservation.getToken());
    } catch (HttpClientErrorException.Conflict exception) {
      throw new ConflictException(
          "tenantId " + requestedTenantId + " is already assigned or reserved");
    }
  }

  /** Returns the authoritative allocation status of one tenant ID. */
  public IdAllocationStatus getAvailability(long tenantId) {
    var availability = createControllerApi().getTenantIdAvailability(tenantId);
    return IdAllocationStatus.valueOf(availability.getStatus().getValue());
  }

  /**
   * Releases an unconsumed reservation so the ID becomes assignable again.
   *
   * @return whether the reservation is now confirmed released; transient failures return false so
   *     callers can retain a durable retry task without masking the original creation failure
   */
  public boolean release(long tenantId) {
    try {
      createControllerApi().releaseTenantIdReservation(tenantId);
      return true;
    } catch (HttpClientErrorException.NotFound exception) {
      log.info("Tenant ID reservation {} was already released", tenantId);
      return true;
    } catch (RestClientException exception) {
      log.error(
          "Failed to release tenant ID reservation {} — possible orphaned reservation in"
              + " TenantService",
          tenantId,
          exception);
      return false;
    }
  }

  private TenantControllerApi createControllerApi() {
    var controllerApi = tenantAdminServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(controllerApi.getApiClient());
    return controllerApi;
  }

  private void addDefaultHeaders(ApiClient apiClient) {
    HttpHeaders headers = this.securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders();
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
