package de.caritas.cob.userservice.api.port.out;

/** Provider-neutral values required to create an identity account. */
public record IdentityAccountCreation(
    String username,
    String email,
    Long tenantId,
    String firstName,
    String lastName,
    String locale) {}
