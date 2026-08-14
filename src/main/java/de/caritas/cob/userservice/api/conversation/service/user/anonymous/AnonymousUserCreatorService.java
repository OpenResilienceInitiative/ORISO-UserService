package de.caritas.cob.userservice.api.conversation.service.user.anonymous;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.conversation.model.AnonymousUserCredentials;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.identity.IdentityProvisioningException;
import de.caritas.cob.userservice.api.facade.CreateUserFacade;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.facade.rollback.RollbackUserAccountInformation;
import de.caritas.cob.userservice.api.port.out.IdentityAccountCreation;
import de.caritas.cob.userservice.api.port.out.IdentityAccountCreator;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.LogService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Service to create anonymous user accounts. */
@Service
@RequiredArgsConstructor
public class AnonymousUserCreatorService {

  private final @NonNull CreateUserFacade createUserFacade;
  private final @NonNull IdentityAccountCreator identityAccountCreator;
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull RollbackFacade rollbackFacade;

  /**
   * Creates an anonymous user account in Keycloak, MariaDB, and Matrix.
   *
   * @param userDto {@link UserDTO}
   * @return {@link AnonymousUserCredentials}
   */
  public AnonymousUserCredentials createAnonymousUser(UserDTO userDto) {

    var createdIdentity =
        identityAccountCreator.createAccount(
            new IdentityAccountCreation(
                userDto.getUsername(),
                userDto.getEmail(),
                userDto.getTenantId(),
                null,
                null,
                userDto.getPreferredLanguage() == null
                    ? null
                    : userDto.getPreferredLanguage().toString()));
    if (createdIdentity == null || isBlank(createdIdentity.userId())) {
      throw new IdentityProvisioningException("Identity user id is missing");
    }
    String identityUserId = createdIdentity.userId();
    // Use the existing "user" realm role instead of "anonymous": the Keycloak realm does not
    // define an "anonymous" role, so assigning it 404s, the password step is skipped, and the
    // subsequent login fails with 401 (breaking invite-link redeem). The anonymous chat endpoints
    // in SecurityConfig all accept USER_DEFAULT, matching how /users/askers/new already registers
    // anonymous chat users (see CreateUserFacade).
    IdentityLogin identityLogin;
    try {
      var user =
          createUserFacade.updateIdentityAndCreateAccount(identityUserId, userDto, UserRole.USER);
      createUserFacade.provisionMatrixUser(user, userDto.getUsername());
      identityLogin = identityAuthentication.login(userDto.getUsername(), userDto.getPassword());
    } catch (BadRequestException | InternalServerErrorException e) {
      rollBackAnonymousUserAccount(identityUserId);
      throw new InternalServerErrorException(e.getMessage(), LogService::logInternalServerError);
    }

    return AnonymousUserCredentials.builder()
        .userId(identityUserId)
        .accessToken(identityLogin.accessToken())
        .expiresIn(identityLogin.expiresIn())
        .refreshToken(identityLogin.refreshToken())
        .refreshExpiresIn(identityLogin.refreshExpiresIn())
        .build();
  }

  private void rollBackAnonymousUserAccount(String userId) {
    rollbackFacade.rollBackUserAccount(
        RollbackUserAccountInformation.builder().userId(userId).rollBackUserAccount(true).build());
  }
}
