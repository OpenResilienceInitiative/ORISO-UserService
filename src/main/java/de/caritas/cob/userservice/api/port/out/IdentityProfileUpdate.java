package de.caritas.cob.userservice.api.port.out;

/** Provider-neutral identity profile values to persist. */
public record IdentityProfileUpdate(
    String username, String email, Long tenantId, String firstName, String lastName) {}
