package de.caritas.cob.userservice.api.port.out;

/** Provider-neutral credentials returned after an identity login. */
public record IdentityLogin(
    String accessToken, int expiresIn, int refreshExpiresIn, String refreshToken) {}
