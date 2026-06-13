package de.caritas.cob.userservice.api.adapters.rocketchat.client;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentialsProvider;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatEndpoints;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatHttpHeaders;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatMapper;
import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.StandardResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.LdapLoginDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.LoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.logout.LogoutResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.RocketChatUserDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UserCreateDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UserDeleteBodyDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UserInfoResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UserUpdateRequestDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UsersListReponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatDeleteUserException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatGetUserIdException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatLoginException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatUserNotInitializedException;
import de.caritas.cob.userservice.api.service.LogService;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/** Client for Rocket.Chat user-related API operations. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RocketChatUserClient {

  private static final String USERS_LIST_ERROR_MESSAGE =
      "Could not get users list from Rocket.Chat";
  private static final String USER_LIST_GET_FIELD_SELECTION = "{\"_id\":1}";

  private final @NonNull RestTemplate restTemplate;
  private final @NonNull RocketChatCredentialsProvider rcCredentialHelper;
  private final @NonNull RocketChatHttpHeaders headersHelper;
  private final @NonNull RocketChatConfig rocketChatConfig;
  private final @NonNull RocketChatClient rocketChatClient;
  private final @NonNull RocketChatMapper mapper;

  /**
   * Creates a new user in Rocket.Chat.
   *
   * @param username the username
   * @param password the password
   * @param email the email address
   * @return the user creation response
   * @throws RocketChatLoginException on failure
   */
  public ResponseEntity<StandardResponseDTO> createUser(
      String username, String password, String email) throws RocketChatLoginException {

    try {
      var systemUserCredentials = rcCredentialHelper.getSystemUser();
      var headers = headersHelper.getStandardHttpHeaders(systemUserCredentials);
      headers.setContentType(MediaType.APPLICATION_JSON);

      var userCreateDTO = new UserCreateDTO();
      userCreateDTO.setName(username);
      userCreateDTO.setEmail(email);
      userCreateDTO.setPassword(password);
      userCreateDTO.setUsername(username);

      HttpEntity<UserCreateDTO> request = new HttpEntity<>(userCreateDTO, headers);

      var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_CREATE);
      return restTemplate.postForEntity(url, request, StandardResponseDTO.class);
    } catch (Exception ex) {
      log.error(
          "Rocket.Chat Error: Could not create user ({}) in Rocket.Chat. Reason", username, ex);
      throw new RocketChatLoginException(
          String.format("Could not create user (%s) in Rocket.Chat", username));
    }
  }

  /**
   * Updates the user data of the given Rocket.Chat user.
   *
   * @param requestDTO the input dto
   * @return the dto containing the user infos
   */
  public UserInfoResponseDTO updateUser(UserUpdateRequestDTO requestDTO) {
    try {
      return updateUserData(requestDTO).getBody();
    } catch (RestClientResponseException | RocketChatUserNotInitializedException ex) {
      throw new InternalServerErrorException(
          String.format("Could not update Rocket.Chat user of user id %s", requestDTO.getUserId()),
          ex,
          LogService::logRocketChatError);
    }
  }

  private ResponseEntity<UserInfoResponseDTO> updateUserData(UserUpdateRequestDTO requestDTO)
      throws RocketChatUserNotInitializedException {
    HttpEntity<UserUpdateRequestDTO> request = buildRocketChatUserUpdateRequestEntity(requestDTO);

    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_UPDATE);

    ResponseEntity<UserInfoResponseDTO> response;
    try {
      response = restTemplate.exchange(url, HttpMethod.POST, request, UserInfoResponseDTO.class);
    } catch (HttpClientErrorException exception) {
      if (exception.getResponseBodyAsString().contains("_syncSendMail")) {
        // after Rocket.Chat issue occurred, try again
        // see: https://github.com/RocketChat/Rocket.Chat/issues/25706
        response = restTemplate.exchange(url, HttpMethod.POST, request, UserInfoResponseDTO.class);
      } else {
        throw exception;
      }
    }

    if (isResponseNotSuccess(response)) {
      throw new InternalServerErrorException(
          String.format(
              "Could not get Rocket.Chat user info of user id %s.%n Status: %s.%n error: %s.%n error type: %s",
              requestDTO.getUserId(),
              response.getStatusCodeValue(),
              response.getBody().getError(),
              response.getBody().getErrorType()),
          LogService::logRocketChatError);
    }

    return response;
  }

  private HttpEntity<UserUpdateRequestDTO> buildRocketChatUserUpdateRequestEntity(
      UserUpdateRequestDTO requestDTO) throws RocketChatUserNotInitializedException {
    RocketChatCredentials technicalUser = rcCredentialHelper.getTechnicalUser();
    var header = headersHelper.getStandardHttpHeaders(technicalUser);
    return new HttpEntity<>(requestDTO, header);
  }

  /**
   * Returns the information of the given Rocket.Chat user.
   *
   * @param rcUserId Rocket.Chat user id
   * @return the dto containing the user infos
   */
  public UserInfoResponseDTO getUserInfo(String rcUserId) {

    ResponseEntity<UserInfoResponseDTO> response;
    try {
      RocketChatCredentials technicalUser = rcCredentialHelper.getTechnicalUser();
      var header = headersHelper.getStandardHttpHeaders(technicalUser);
      HttpEntity<Void> request = new HttpEntity<>(header);

      var fields = "{\"userRooms\":1}";
      var url =
          rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_INFO + rcUserId) + "&fields={fields}";
      response =
          restTemplate.exchange(url, HttpMethod.GET, request, UserInfoResponseDTO.class, fields);

    } catch (RestClientResponseException | RocketChatUserNotInitializedException ex) {
      throw new InternalServerErrorException(
          String.format("Could not get Rocket.Chat user info of user id %s", rcUserId),
          ex,
          LogService::logRocketChatError);
    }

    if (isResponseNotSuccess(response)) {
      throw new InternalServerErrorException(
          String.format(
              "Could not get Rocket.Chat user info of user id %s.%n Status: %s.%n error: %s.%n error type: %s",
              rcUserId,
              response.getStatusCodeValue(),
              response.getBody().getError(),
              response.getBody().getErrorType()),
          LogService::logRocketChatError);
    }

    return response.getBody();
  }

  private boolean isResponseNotSuccess(ResponseEntity<UserInfoResponseDTO> response) {
    UserInfoResponseDTO responseBody = response.getBody();
    return isNull(responseBody)
        || response.getStatusCode() != HttpStatus.OK
        || !responseBody.isSuccess();
  }

  /**
   * Deletes the user data of the given Rocket.Chat user.
   *
   * @param rcUserId Rocket.Chat user id
   * @throws RocketChatDeleteUserException when deletion of user fails
   */
  public void deleteUser(String rcUserId) throws RocketChatDeleteUserException {
    try {
      deleteUserData(rcUserId);
    } catch (Exception e) {
      throw new RocketChatDeleteUserException(e);
    }
  }

  private void deleteUserData(String rcUserId) throws RocketChatUserNotInitializedException {

    var requestDTO = new UserDeleteBodyDTO(rcUserId);
    RocketChatCredentials technicalUser = rcCredentialHelper.getTechnicalUser();
    var header = headersHelper.getStandardHttpHeaders(technicalUser);
    HttpEntity<UserDeleteBodyDTO> request = new HttpEntity<>(requestDTO, header);

    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_DELETE);
    var response = restTemplate.exchange(url, HttpMethod.POST, request, UserInfoResponseDTO.class);

    if (isResponseNotSuccess(response) && !isUserAlreadyDeleted(response)) {
      throw new InternalServerErrorException(
          String.format(
              "Could not delete Rocket.Chat user with user id %s.%n Status: %s.%n error: %s.%n "
                  + "error type: %s",
              rcUserId,
              response.getStatusCodeValue(),
              response.getBody().getError(),
              response.getBody().getErrorType()),
          LogService::logRocketChatError);
    }
  }

  private boolean isUserAlreadyDeleted(ResponseEntity<UserInfoResponseDTO> response) {
    var b = response.getBody();

    return response.getStatusCode().is4xxClientError()
        && nonNull(b)
        && nonNull(b.getError())
        && b.getError().contains("error-invalid-user");
  }

  /**
   * Retrieves the userId for the given credentials.
   *
   * @param username the username
   * @param password the password
   * @param firstLogin true, if first login in Rocket.Chat. This requires a special API call.
   * @return the userid
   * @throws RocketChatLoginException on failure
   */
  public String getUserID(String username, String password, boolean firstLogin)
      throws RocketChatLoginException {

    ResponseEntity<LoginResponseDTO> response;

    if (firstLogin) {
      response = loginUserFirstTime(username, password);
    } else {
      response = this.rcCredentialHelper.loginUser(username, password);
    }

    LoginResponseDTO body = response.getBody();
    if (body != null) {
      var rocketChatCredentialsLocal =
          RocketChatCredentials.builder()
              .rocketChatUserId(body.getData().getUserId())
              .rocketChatToken(body.getData().getAuthToken())
              .build();
      logoutUser(rocketChatCredentialsLocal);
      return rocketChatCredentialsLocal.getRocketChatUserId();
    } else {
      throw new RocketChatLoginException("Could not login user in Rocket.Chat");
    }
  }

  /**
   * Initial login to synchronize ldap and Rocket.Chat user.
   *
   * @param username the username
   * @param password the password
   * @return the login result
   * @throws RocketChatLoginException on failure
   */
  public ResponseEntity<LoginResponseDTO> loginUserFirstTime(String username, String password)
      throws RocketChatLoginException {

    try {
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      var ldapLoginDTO = new LdapLoginDTO();
      ldapLoginDTO.setLdap(true);
      ldapLoginDTO.setUsername(username);
      ldapLoginDTO.setLdapPass(password);

      HttpEntity<LdapLoginDTO> request = new HttpEntity<>(ldapLoginDTO, headers);

      var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_LOGIN);
      return restTemplate.postForEntity(url, request, LoginResponseDTO.class);
    } catch (Exception ex) {
      log.error(
          "Rocket.Chat Error: Could not login user ({}) in Rocket.Chat for the first time. Reason",
          username,
          ex);
      throw new RocketChatLoginException(
          String.format("Could not login user (%s) in Rocket.Chat for the first time", username));
    }
  }

  /**
   * Performs a logout with the given credentials and returns true on success.
   *
   * @param rocketChatCredentials {@link RocketChatCredentials}
   * @return true if logout was successful
   */
  public boolean logoutUser(RocketChatCredentials rocketChatCredentials) {

    try {
      var headers = headersHelper.getStandardHttpHeaders(rocketChatCredentials);

      HttpEntity<Void> request = new HttpEntity<>(headers);

      var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_LOGOUT);
      var response = restTemplate.postForEntity(url, request, LogoutResponseDTO.class);

      return response.getStatusCode() == HttpStatus.OK;

    } catch (Exception ex) {
      log.error(
          "Rocket.Chat Error: Could not log out user id ({}) from Rocket.Chat",
          rocketChatCredentials.getRocketChatUserId(),
          ex);

      return false;
    }
  }

  /**
   * Returns the id of a Rocket.Chat user by username.
   *
   * @param username the username to search for
   * @return the Rocket.Chat user id
   * @throws RocketChatGetUserIdException when request fails
   */
  public String getRocketChatUserIdByUsername(String username) throws RocketChatGetUserIdException {

    ResponseEntity<UsersListReponseDTO> response;
    try {
      var technicalUser = rcCredentialHelper.getTechnicalUser();
      var header = headersHelper.getStandardHttpHeaders(technicalUser);
      HttpEntity<UsersListReponseDTO> request = new HttpEntity<>(header);
      response =
          restTemplate.exchange(
              buildUsersListGetUrl(),
              HttpMethod.GET,
              request,
              UsersListReponseDTO.class,
              buildUsernameQuery(username),
              USER_LIST_GET_FIELD_SELECTION);
    } catch (Exception ex) {
      throw new RocketChatGetUserIdException(USERS_LIST_ERROR_MESSAGE, ex);
    }

    if (response.getStatusCode() == HttpStatus.OK && nonNull(response.getBody())) {
      return extractUserIdFromResponse(response.getBody().getUsers());
    } else {
      throw new RocketChatGetUserIdException(USERS_LIST_ERROR_MESSAGE);
    }
  }

  private String extractUserIdFromResponse(RocketChatUserDTO[] users)
      throws RocketChatGetUserIdException {

    if (users.length == 1) {
      return users[0].getId();
    }

    throw new RocketChatGetUserIdException(
        String.format("Found %s users by username", users.length));
  }

  private String buildUsersListGetUrl() {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_LIST);
    return url + "?query={query}&fields={fields}";
  }

  private String buildUsernameQuery(String username) {
    return String.format("{\"username\":{\"$eq\":\"%s\"}}", username.toLowerCase());
  }

  public Optional<Map<String, Object>> findUser(String chatUserId) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_INFO + chatUserId);

    try {
      var response = rocketChatClient.getForEntity(url, UserInfoResponseDTO.class);
      return mapper.mapOfUserResponse(response);
    } catch (HttpClientErrorException exception) {
      log.error("User Info failed.", exception);
      return Optional.empty();
    }
  }

  public Optional<Map<String, Object>> findUserAndAddToCache(String chatUserId) {
    return findUser(chatUserId);
  }
}
