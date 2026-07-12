package de.caritas.cob.userservice.api.conversation.service.user.anonymous;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.LoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.conversation.model.AnonymousUserCredentials;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatLoginException;
import de.caritas.cob.userservice.api.facade.CreateUserFacade;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.facade.rollback.RollbackUserAccountInformation;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.user.UserService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/** Service to create anonymous user accounts. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnonymousUserCreatorService {

  /**
   * Matrix migration placeholder — mirrors {@code CreateEnquiryMessageFacade}. Downstream room
   * creation skips all Rocket.Chat operations when it sees this dummy user id.
   */
  private static final String MATRIX_MIGRATION_DUMMY_RC_USER_ID = "matrix-migration-dummy-user";

  private static final String MATRIX_MIGRATION_DUMMY_RC_TOKEN = "matrix-migration-dummy-token";

  private final @NonNull CreateUserFacade createUserFacade;
  private final @NonNull IdentityClient identityClient;
  private final @NonNull RocketChatService rocketChatService;
  private final @NonNull RollbackFacade rollbackFacade;
  private final @NonNull UserService userService;
  private final @NonNull UserHelper userHelper;

  /** ADR-004: with Rocket.Chat disabled no Rocket.Chat account is created or logged in. */
  @Value("${rocket-chat.enabled:false}")
  private boolean rocketChatEnabled;

  /**
   * Creates an anonymous user account in Keycloak, MariaDB and Rocket.Chat.
   *
   * @param userDto {@link UserDTO}
   * @return {@link AnonymousUserCredentials}
   */
  public AnonymousUserCredentials createAnonymousUser(UserDTO userDto) {

    KeycloakCreateUserResponseDTO response = identityClient.createKeycloakUser(userDto);
    // Use the existing "user" realm role instead of "anonymous": the Keycloak realm does not
    // define an "anonymous" role, so assigning it 404s, the password step is skipped, and the
    // subsequent login fails with 401 (breaking invite-link redeem). The anonymous chat endpoints
    // in SecurityConfig all accept USER_DEFAULT, matching how /users/askers/new already registers
    // anonymous chat users (see CreateUserFacade).
    var createdUser =
        createUserFacade.updateIdentityAndCreateAccount(
            response.getUserId(), userDto, UserRole.USER);

    // ADR-004 Matrix-only: anonymous askers need a Matrix account too, otherwise their first
    // enquiry fails with "has no Matrix account". The registered path provisions Matrix inside
    // createUserAccountWithInitializedConsultingType; the anonymous path calls only the inner
    // account creation, so provision Matrix explicitly here when Rocket.Chat is disabled.
    if (!rocketChatEnabled) {
      createUserFacade.ensureMatrixUser(createdUser, userDto.getUsername());
    }

    KeycloakLoginResponseDTO kcLoginResponseDTO;
    ResponseEntity<LoginResponseDTO> rcLoginResponseDto = null;
    try {
      kcLoginResponseDTO = identityClient.loginUser(userDto.getUsername(), userDto.getPassword());
      if (rocketChatEnabled) {
        ensureRocketChatUserExists(userDto, response.getUserId());
        rcLoginResponseDto = loginRocketChatUser(userDto.getUsername(), userDto.getPassword());
      }
    } catch (RocketChatLoginException | BadRequestException e) {
      rollBackAnonymousUserAccount(response.getUserId());
      throw new InternalServerErrorException(e.getMessage(), LogService::logInternalServerError);
    }

    var anonymousUserCredentials =
        AnonymousUserCredentials.builder()
            .userId(response.getUserId())
            .accessToken(kcLoginResponseDTO.getAccessToken())
            .expiresIn(kcLoginResponseDTO.getExpiresIn())
            .refreshToken(kcLoginResponseDTO.getRefreshToken())
            .refreshExpiresIn(kcLoginResponseDTO.getRefreshExpiresIn())
            .rocketChatCredentials(
                rocketChatEnabled
                    ? obtainRocketChatCredentials(rcLoginResponseDto)
                    : matrixMigrationDummyCredentials())
            .build();

    if (rocketChatEnabled) {
      updateRocketChatUserIdInDatabase(anonymousUserCredentials);
    }

    return anonymousUserCredentials;
  }

  private RocketChatCredentials matrixMigrationDummyCredentials() {
    return RocketChatCredentials.builder()
        .rocketChatUserId(MATRIX_MIGRATION_DUMMY_RC_USER_ID)
        .rocketChatToken(MATRIX_MIGRATION_DUMMY_RC_TOKEN)
        .build();
  }

  private void ensureRocketChatUserExists(UserDTO userDto, String keycloakUserId)
      throws RocketChatLoginException {
    String encodedUsername = userDto.getUsername();
    String email =
        StringUtils.isNotBlank(userDto.getEmail())
            ? userDto.getEmail()
            : userHelper.getDummyEmail(keycloakUserId);
    try {
      var createResponse =
          rocketChatService.createUser(encodedUsername, userDto.getPassword(), email);
      if (createResponse.getBody() != null && !createResponse.getBody().isSuccess()) {
        String error = createResponse.getBody().getError();
        if (!isRocketChatUserAlreadyExists(error)) {
          throw new RocketChatLoginException(
              String.format(
                  "Could not create user (%s) in Rocket.Chat: %s", encodedUsername, error));
        }
        log.warn("Rocket.Chat user {} already exists: {}", encodedUsername, error);
      }
    } catch (RocketChatLoginException e) {
      if (!isRocketChatUserAlreadyExists(e.getMessage())) {
        throw e;
      }
      log.warn(
          "Rocket.Chat user {} might already exist, continuing with login: {}",
          encodedUsername,
          e.getMessage());
    }
  }

  /**
   * Users created via {@link RocketChatService#createUser} must log in with the native login API.
   * LDAP first-login only applies to accounts provisioned from Keycloak/LDAP.
   */
  private ResponseEntity<LoginResponseDTO> loginRocketChatUser(String username, String password)
      throws RocketChatLoginException {
    try {
      return rocketChatService.loginWithPassword(username, password);
    } catch (RocketChatLoginException nativeLoginEx) {
      log.warn(
          "Native Rocket.Chat login failed for {}, trying LDAP first-login: {}",
          username,
          nativeLoginEx.getMessage());
      return rocketChatService.loginUserFirstTime(username, password);
    }
  }

  private static boolean isRocketChatUserAlreadyExists(String errorMessage) {
    if (errorMessage == null) {
      return false;
    }
    String lower = errorMessage.toLowerCase();
    return lower.contains("already exists")
        || lower.contains("in use")
        || lower.contains("duplicate");
  }

  private void rollBackAnonymousUserAccount(String userId) {
    rollbackFacade.rollBackUserAccount(
        RollbackUserAccountInformation.builder().userId(userId).rollBackUserAccount(true).build());
  }

  private RocketChatCredentials obtainRocketChatCredentials(
      ResponseEntity<LoginResponseDTO> response) {
    return RocketChatCredentials.builder()
        .rocketChatUserId(response.getBody().getData().getUserId())
        .rocketChatToken(response.getBody().getData().getAuthToken())
        .build();
  }

  private void updateRocketChatUserIdInDatabase(AnonymousUserCredentials anonymousUserCredentials) {
    var user =
        userService
            .getUser(anonymousUserCredentials.getUserId())
            .orElseThrow(
                () ->
                    new InternalServerErrorException(
                        String.format(
                            "Could not get user %s to update the rocket chat user id.",
                            anonymousUserCredentials.getUserId())));
    userService.updateRocketChatIdInDatabase(
        user, anonymousUserCredentials.getRocketChatCredentials().getRocketChatUserId());
  }
}
