package de.caritas.cob.userservice.api.service.accountinvite.allocation;

/**
 * A successful tenant-ID reservation issued by TenantService (TEN-INV-U1).
 *
 * @param tenantId the reserved tenant ID (AUTO mode: assigned by TenantService)
 * @param token reservation token; must be kept with the invite because tenant creation consumes the
 *     reserved ID only against this token ({@code MultilingualTenantDTO.tenantIdReservationToken})
 */
public record TenantIdReservation(long tenantId, String token) {}
