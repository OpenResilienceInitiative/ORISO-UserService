package de.caritas.cob.userservice.api.service.user.validation;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Validation class for user accounts. */
@Component
@RequiredArgsConstructor
public class UserAccountValidator {

  private final @NotNull IdentityAuthentication identityAuthentication;

  /**
   * Checks if user can be logged in via the provided credentials. If password is wrong a {@link
   * BadRequestException} is thrown by {@link IdentityAuthentication}.
   *
   * @param username username
   * @param password password
   */
  public void checkPasswordValidity(String username, String password) {
    var loginResponse = identityAuthentication.login(username, password);
    identityAuthentication.logout(loginResponse.refreshToken());
  }
}
