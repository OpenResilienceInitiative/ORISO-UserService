package de.caritas.cob.userservice.api.port.out;

/** Provider-neutral profile data owned by the external identity provider. */
public record IdentityProfile(
    String id, String username, String firstName, String lastName, String email) {}
