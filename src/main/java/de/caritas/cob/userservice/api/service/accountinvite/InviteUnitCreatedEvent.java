package de.caritas.cob.userservice.api.service.accountinvite;

/**
 * Waiting invites are released only after the publishing transaction commits. For a tenant, {@code
 * tenantId} equals {@code unitId}.
 */
public record InviteUnitCreatedEvent(InviteUnitType unitType, Long unitId, Long tenantId) {}
