package de.caritas.cob.userservice.api.port.out;

/** Provider-neutral identity profile required by the authenticated user-data facade. */
public record IdentityProfile(
    String id, String username, String firstName, String lastName, String email) {}
