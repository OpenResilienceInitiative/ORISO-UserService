package de.caritas.cob.userservice.api.port.out;

/** Provider-neutral authenticated session returned by an identity-provider exchange. */
public record IdentitySession(
    String accessToken,
    int expiresIn,
    int refreshExpiresIn,
    String refreshToken,
    String tokenType,
    String sessionState,
    String scope) {}
