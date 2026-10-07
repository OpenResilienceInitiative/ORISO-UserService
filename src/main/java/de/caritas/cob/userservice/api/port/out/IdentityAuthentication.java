package de.caritas.cob.userservice.api.port.out;

/** Provider-neutral identity authentication contract. */
public interface IdentityAuthentication {

  IdentityLogin login(String username, String password);

  /** Confidential backend grant. Its result has no human refresh-session token. */
  IdentityLogin loginService(String clientId, String clientSecret);

  /** Explicit task grant; validates the issued subject and client against its configuration. */
  default IdentityLogin loginTask(
      de.caritas.cob.userservice.api.config.auth.TaskIdentityCredentials identity) {
    throw new UnsupportedOperationException(
        "Explicit task identity grants are not supported by this provider");
  }

  boolean logout(String refreshToken);

  /** Ends a caller-owned session without requiring a current HTTP request. */
  default boolean logout(String refreshToken, String accessToken) {
    return logout(refreshToken);
  }

  boolean verifyPasswordIgnoringSecondFactor(String username, String password);
}
