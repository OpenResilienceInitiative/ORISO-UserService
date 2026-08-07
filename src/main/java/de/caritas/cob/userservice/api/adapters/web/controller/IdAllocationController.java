package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import java.util.function.LongFunction;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

/**
 * Aggregated live validation of tenant and agency IDs for the Admin invite composer (TEN-INV-U3,
 * ORISO-UserService#889).
 *
 * <p>Proxies the authoritative allocation state from TenantService ({@code
 * /tenantadmin/tenant-ids/**}) and AgencyService ({@code /agencyadmin/agencyids/**}) in one call.
 * The response is advisory for the UI only — reservation happens server-side on invite creation, so
 * a stale green state here never produces a duplicate ID.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class IdAllocationController {

  private static final String ADMIN_AUTH =
      "hasAnyAuthority('AUTHORIZATION_TENANT_ADMIN', 'AUTHORIZATION_USER_ADMIN',"
          + " 'AUTHORIZATION_RESTRICTED_AGENCY_ADMIN')";

  private final @NonNull TenantIdAllocationClient tenantIdAllocationClient;
  private final @NonNull AgencyIdAllocationClient agencyIdAllocationClient;

  /**
   * Returns the allocation state of the requested IDs. At least one of {@code tenantId} and {@code
   * agencyId} is required; each entry is independent, so a failing upstream service degrades only
   * its own entry to {@code SERVICE_ERROR} (with the upstream HTTP status passed through) instead
   * of failing the whole validation.
   */
  @PreAuthorize(ADMIN_AUTH)
  @GetMapping("/useradmin/id-allocation")
  public ResponseEntity<IdAllocationValidationResponseDTO> validateIds(
      @RequestParam(value = "tenantId", required = false) Long tenantId,
      @RequestParam(value = "agencyId", required = false) Long agencyId) {
    if (tenantId == null && agencyId == null) {
      throw new BadRequestException("At least one of tenantId and agencyId is required");
    }
    var response = new IdAllocationValidationResponseDTO();
    if (tenantId != null) {
      response.tenant = resolve(tenantId, tenantIdAllocationClient::getAvailability, "tenant");
    }
    if (agencyId != null) {
      response.agency = resolve(agencyId, agencyIdAllocationClient::getAvailability, "agency");
    }
    return ResponseEntity.ok(response);
  }

  private static IdAllocationEntryDTO resolve(
      long id, LongFunction<IdAllocationStatus> availability, String kind) {
    var entry = new IdAllocationEntryDTO();
    entry.id = id;
    try {
      entry.status = availability.apply(id).name();
    } catch (RestClientResponseException exception) {
      log.warn(
          "ID allocation lookup for {} ID {} failed upstream with status {}",
          kind,
          id,
          exception.getStatusCode().value());
      entry.status = IdAllocationEntryDTO.SERVICE_ERROR;
      entry.upstreamStatus = exception.getStatusCode().value();
    } catch (RuntimeException exception) {
      log.warn("ID allocation lookup for {} ID {} failed: {}", kind, id, exception.getMessage());
      entry.status = IdAllocationEntryDTO.SERVICE_ERROR;
    }
    return entry;
  }

  /** One validated ID: FREE / RESERVED / ASSIGNED, or SERVICE_ERROR when the lookup failed. */
  public static class IdAllocationEntryDTO {
    public static final String SERVICE_ERROR = "SERVICE_ERROR";

    public Long id;
    public String status;

    /** Upstream HTTP status, only set when {@link #status} is {@code SERVICE_ERROR}. */
    public Integer upstreamStatus;
  }

  public static class IdAllocationValidationResponseDTO {
    public IdAllocationEntryDTO tenant;
    public IdAllocationEntryDTO agency;
  }
}
