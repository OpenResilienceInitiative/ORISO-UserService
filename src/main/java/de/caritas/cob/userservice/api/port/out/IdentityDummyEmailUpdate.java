package de.caritas.cob.userservice.api.port.out;

/** Provider-neutral identity values retained while replacing a blank email with a dummy address. */
public record IdentityDummyEmailUpdate(String username, Long tenantId) {}
