package de.caritas.cob.userservice.api.service.accountinvite;

/**
 * A unit that invites may wait for now exists (ORISO-Admin#1026, slice 5): its first admin finished
 * onboarding and the agency / tenant row exists. Published by the onboarding flows; the invites
 * waiting for the unit are released after the publishing transaction committed.
 *
 * @param unitType AGENCY or TENANT
 * @param unitId the agency ID or the tenant ID
 * @param tenantId the unit's tenant (equals {@code unitId} for a tenant)
 */
public record InviteUnitCreatedEvent(InviteUnitType unitType, Long unitId, Long tenantId) {}
