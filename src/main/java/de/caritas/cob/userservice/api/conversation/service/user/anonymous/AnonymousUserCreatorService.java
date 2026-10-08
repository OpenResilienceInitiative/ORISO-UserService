package de.caritas.cob.userservice.api.conversation.service.user.anonymous;

import de.caritas.cob.userservice.api.adapters.keycloak.commands.*;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.conversation.model.AnonymousUserCredentials;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.facade.CreateUserFacade;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.facade.rollback.RollbackUserAccountInformation;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.service.LogService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Service to create anonymous user accounts. */
@Service
@RequiredArgsConstructor
public class AnonymousUserCreatorService {

  private final @NonNull CreateUserFacade createUserFacade;
  private final @NonNull IdentityAccountProvisioning identityProvisioning;
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull RollbackFacade rollbackFacade;

  /**
   * Creates an anonymous user account in Keycloak, MariaDB, and Matrix.
   *
   * @param userDto {@link UserDTO}
   * @return {@link AnonymousUserCredentials}
   */
  @org.springframework.transaction.annotation.Transactional
  public AnonymousUserCredentials createAnonymousUser(
      UserDTO userDto, IdentityCreationOrigin origin) {
    if (!"ANONYMOUS".equals(origin.originKindForPolicy())
        || !"ANONYMOUS".equals(origin.registrationKind()))
      throw new org.springframework.security.access.AccessDeniedException(
          "Anonymous creation requires its verified registration origin");
    var receipt =
        identityProvisioning.create(
            java.util.UUID.randomUUID(), CreateUserFacade.initialIdentity(userDto, origin), origin);
    identityProvisioning.acquireLocalSaga(receipt);
    String identityUserId = receipt.accountId();
    // Use the existing "user" realm role instead of "anonymous": the Keycloak realm does not
    // define an "anonymous" role, so assigning it 404s, the password step is skipped, and the
    // subsequent login fails with 401 (breaking invite-link redeem). The anonymous chat endpoints
    // in SecurityConfig all accept USER_DEFAULT, matching how /users/askers/new already registers
    // anonymous chat users (see CreateUserFacade).
    de.caritas.cob.userservice.api.model.User createdUser = null;
    try {
      var user =
          createUserFacade.updateIdentityAndCreateAccount(identityUserId, userDto, UserRole.USER);
      createdUser = user;
      createUserFacade.provisionOwnedMatrixUser(user, userDto.getUsername(), receipt);

    } catch (RuntimeException e) {
      try {
        rollBackAnonymousUserAccount(identityUserId, createdUser);
      } catch (RuntimeException rollbackFailure) {
        e.addSuppressed(rollbackFailure);
      }
      throw new InternalServerErrorException(e.getMessage(), LogService::logInternalServerError);
    }

    return AnonymousUserCredentials.builder().userId(identityUserId).build();
  }

  /** Only after the complete local saga and native activation succeeded. */
  public AnonymousUserCredentials authenticateCreatedUser(
      UserDTO userDto, AnonymousUserCredentials created) {
    var login = identityAuthentication.login(userDto.getUsername(), userDto.getPassword());
    return AnonymousUserCredentials.builder()
        .userId(created.getUserId())
        .accessToken(login.accessToken())
        .expiresIn(login.expiresIn())
        .refreshToken(login.refreshToken())
        .refreshExpiresIn(login.refreshExpiresIn())
        .build();
  }

  private void rollBackAnonymousUserAccount(
      String userId, de.caritas.cob.userservice.api.model.User createdUser) {
    rollbackFacade.rollBackUserAccount(
        RollbackUserAccountInformation.builder()
            .userId(userId)
            .user(createdUser)
            .rollBackUserAccount(true)
            .build());
  }
}
