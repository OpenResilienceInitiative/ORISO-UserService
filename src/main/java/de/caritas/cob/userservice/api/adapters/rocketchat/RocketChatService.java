package de.caritas.cob.userservice.api.adapters.rocketchat;

import static java.util.Arrays.asList;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.rocketchat.client.RocketChatGroupClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.client.RocketChatMessageClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.client.RocketChatRoomClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.client.RocketChatUserClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.StandardResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupMemberDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.LoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.PresenceDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.PresenceListDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.message.MessageResponse;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomsUpdateDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.subscriptions.SubscriptionsGetDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.subscriptions.SubscriptionsUpdateDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UserInfoResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UserUpdateRequestDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.RocketChatUnauthorizedException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatAddUserToGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatCreateGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatDeleteGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatDeleteUserException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatGetGroupMembersException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatGetGroupsListAllException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatGetUserIdException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatLeaveFromGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatLoginException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatRemoveSystemMessagesException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatRemoveUserFromGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatUserNotInitializedException;
import de.caritas.cob.userservice.api.port.out.MessageClient;
import de.caritas.cob.userservice.api.service.LogService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.PostConstruct;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/** Service for Rocket.Chat functionalities. */
@Slf4j
@Getter
@Service
@RequiredArgsConstructor
public class RocketChatService implements MessageClient {

  private final @NonNull RestTemplate restTemplate;
  private final @NonNull RocketChatCredentialsProvider rcCredentialHelper;
  private final @NonNull RocketChatHttpHeaders headersHelper;

  private final RocketChatClient rocketChatClient;

  private final RocketChatUserClient rocketChatUserClient;

  private final RocketChatGroupClient rocketChatGroupClient;

  private final RocketChatRoomClient rocketChatRoomClient;

  private final RocketChatMessageClient rocketChatMessageClient;

  private final RocketChatConfig rocketChatConfig;

  private final RocketChatMapper mapper;

  private final RocketChatCredentials rocketChatCredentials;

  private boolean rotatingTokensInitialized = false;

  @PostConstruct
  @Scheduled(cron = "#{rocketChatConfig.credentialCron}")
  @Profile("!testing")
  public void updateCredentials() {
    if (rotatingTokensInitialized) {
      log.debug("rotating tokens");
    } else {
      log.debug("initialize tokens");
      rotatingTokensInitialized = true;
    }

    try {
      rcCredentialHelper.updateCredentials();
    } catch (RocketChatLoginException e) {
      log.warn("Unauthorized: {}", e.getMessage());
    }
  }

  @Override
  public boolean muteUserInChat(String username, String roomId) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_MUTE);
    var muteUser = mapper.muteUserOf(username, roomId);

    try {
      var response = rocketChatClient.postForEntity(url, muteUser, MessageResponse.class);
      return userWasInRoom(response) && response.getStatusCode().is2xxSuccessful();
    } catch (HttpClientErrorException exception) {
      log.error("Muting failed.", exception);
      return false;
    }
  }

  @Override
  public boolean unmuteUserInChat(String username, String roomId) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_UNMUTE);
    var unmuteUser = mapper.unmuteUserOf(username, roomId);

    try {
      var response = rocketChatClient.postForEntity(url, unmuteUser, MessageResponse.class);
      return response.getStatusCode().is2xxSuccessful();
    } catch (HttpClientErrorException exception) {
      log.error("Un-muting failed.", exception);
      return false;
    }
  }

  /**
   * Updates the user data of the given Rocket.Chat user.
   *
   * @param requestDTO the input dto
   * @return the dto containing the user infos
   */
  public UserInfoResponseDTO updateUser(UserUpdateRequestDTO requestDTO) {
    return rocketChatUserClient.updateUser(requestDTO);
  }

  @Override
  public boolean updateUser(String chatUserId, String displayName) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_UPDATE);
    var updateUser = mapper.updateUserOf(chatUserId, displayName);

    try {
      var response = rocketChatClient.postForEntity(url, chatUserId, updateUser, Void.class);
      return response.getStatusCode().is2xxSuccessful();
    } catch (HttpClientErrorException exception) {
      log.error("Setting display failed.", exception);
      return false;
    }
  }

  @Override
  public Set<String> findAllAvailableUserIds() {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_PRESENCE_LIST);

    try {
      var presentList = rocketChatClient.getForEntity(url, PresenceListDTO.class).getBody();
      if (isNull(presentList)) {
        log.warn("Present user search inconclusive");
      } else {
        return mapper.mapAvailableOf(presentList);
      }
    } catch (HttpClientErrorException exception) {
      log.error("Present user search failed.", exception);
    }

    return Set.of();
  }

  @Override
  public Optional<Boolean> isLoggedIn(String chatUserId) {
    return getUserPresence(chatUserId).flatMap(presenceDTO -> Optional.of(presenceDTO.isPresent()));
  }

  @Override
  public Optional<Boolean> isAvailable(String chatUserId) {
    return getUserPresence(chatUserId)
        .flatMap(presenceDTO -> Optional.of(presenceDTO.isAvailable()));
  }

  private Optional<PresenceDTO> getUserPresence(String chatUserId) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_PRESENCE_GET + chatUserId);

    try {
      var body = rocketChatClient.getForEntity(url, PresenceDTO.class).getBody();
      if (isNull(body)) {
        log.warn("Presence check inconclusive (user \"{}\".)", chatUserId);
      } else {
        return Optional.of(body);
      }
    } catch (HttpClientErrorException exception) {
      log.error("Presence check failed.", exception);
    }

    return Optional.empty();
  }

  @Override
  public boolean setUserPresence(String username, String status) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.USER_PRESENCE_SET);
    var userPresence = mapper.setUserPresenceOf(status);

    try {
      var response =
          rocketChatClient.postForEntity(url, username, userPresence, MessageResponse.class);
      return isSuccessful(response);
    } catch (HttpClientErrorException exception) {
      log.error("Setting user presence failed.", exception);
      return false;
    }
  }

  private boolean isSuccessful(ResponseEntity<MessageResponse> response) {
    var body = response.getBody();

    return nonNull(body) && body.getSuccess() && !body.getMessage().contains("\"error\"");
  }

  @Override
  public Optional<Map<String, Object>> findUser(String chatUserId) {
    return rocketChatUserClient.findUser(chatUserId);
  }

  @Override
  @Cacheable(key = "#chatUserId", value = "rocketChatUserCache")
  public Optional<Map<String, Object>> findUserAndAddToCache(String chatUserId) {
    return rocketChatUserClient.findUserAndAddToCache(chatUserId);
  }

  @Override
  public Optional<Map<String, Object>> getChatInfo(String roomId) {
    return rocketChatRoomClient.getChatInfo(roomId);
  }

  /**
   * Creation of a private Rocket.Chat group.
   *
   * @param rocketChatCredentials {@link RocketChatCredentials}
   * @return the group id
   */
  public Optional<GroupResponseDTO> createPrivateGroup(
      String name, RocketChatCredentials rocketChatCredentials)
      throws RocketChatCreateGroupException {
    return rocketChatGroupClient.createPrivateGroup(name, rocketChatCredentials);
  }

  /**
   * Creates a private Rocket.Chat group with the system user (credentials).
   *
   * @param groupName the Rocket.Chat group name
   * @return an {@link Optional} of a {@link GroupResponseDTO}
   * @throws RocketChatCreateGroupException on failure
   */
  public Optional<GroupResponseDTO> createPrivateGroupWithSystemUser(String groupName)
      throws RocketChatCreateGroupException {
    return rocketChatGroupClient.createPrivateGroupWithSystemUser(groupName);
  }

  /**
   * Deletion of a Rocket.Chat group as system user.
   *
   * @param groupId the Rocket.Chat group id
   * @return true, if successfully
   */
  public boolean deleteGroupAsSystemUser(String groupId) {
    return rocketChatGroupClient.deleteGroupAsSystemUser(groupId);
  }

  /**
   * Deletion of a Rocket.Chat group as technical user.
   *
   * @param groupId the Rocket.Chat group id
   * @throws RocketChatDeleteGroupException when deletion of group fails
   */
  public void deleteGroupAsTechnicalUser(String groupId) throws RocketChatDeleteGroupException {
    rocketChatGroupClient.deleteGroupAsTechnicalUser(groupId);
  }

  /**
   * Deletion of a Rocket.Chat group.
   *
   * @param groupId the group id
   * @param rocketChatCredentials {@link RocketChatCredentials}
   * @return true, if successfully
   */
  public boolean rollbackGroup(String groupId, RocketChatCredentials rocketChatCredentials) {
    return rocketChatGroupClient.rollbackGroup(groupId, rocketChatCredentials);
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
    return rocketChatUserClient.getUserID(username, password, firstLogin);
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
    return rocketChatUserClient.loginUserFirstTime(username, password);
  }

  /**
   * Performs a logout with the given credentials and returns true on success.
   *
   * @param rocketChatCredentials {@link RocketChatCredentials}
   * @return true if logout was successful
   */
  public boolean logoutUser(RocketChatCredentials rocketChatCredentials) {
    return rocketChatUserClient.logoutUser(rocketChatCredentials);
  }

  /**
   * Adds the provided user to the Rocket.Chat group with given groupId.
   *
   * @param rcUserId Rocket.Chat userId
   * @param rcGroupId Rocket.Chat roomId
   */
  public void addUserToGroup(String rcUserId, String rcGroupId)
      throws RocketChatAddUserToGroupException {
    rocketChatGroupClient.addUserToGroup(rcUserId, rcGroupId);
  }

  /**
   * Adds the technical user to the given Rocket.Chat group id.
   *
   * @param rcGroupId the rocket chat group id
   */
  public void addTechnicalUserToGroup(String rcGroupId)
      throws RocketChatAddUserToGroupException, RocketChatUserNotInitializedException {
    rocketChatGroupClient.addTechnicalUserToGroup(rcGroupId);
  }

  /**
   * Leave from the Rocket.Chat group with given groupId as the technical user.
   *
   * @param rcGroupId Rocket.Chat roomId
   * @throws RocketChatLeaveFromGroupException on failure
   */
  public void leaveFromGroupAsTechnicalUser(String rcGroupId)
      throws RocketChatLeaveFromGroupException {
    rocketChatGroupClient.leaveFromGroupAsTechnicalUser(rcGroupId);
  }

  /**
   * Removes the provided user from the Rocket.Chat group with given groupId.
   *
   * @param rcUserId Rocket.Chat userId
   * @param rcGroupId Rocket.Chat roomId
   * @throws RocketChatRemoveUserFromGroupException on failure
   */
  public void removeUserFromGroup(String rcUserId, String rcGroupId)
      throws RocketChatRemoveUserFromGroupException {
    rocketChatGroupClient.removeUserFromGroup(rcUserId, rcGroupId);
  }

  @Override
  public boolean removeUserFromSession(String chatUserId, String chatId) {
    return rocketChatGroupClient.removeUserFromSession(chatUserId, chatId);
  }

  /**
   * Get all standard members (all users except system user and technical user) of a rocket chat
   * group.
   *
   * @param rcGroupId the rocket chat group id
   * @return all standard members of that group
   */
  public List<GroupMemberDTO> getStandardMembersOfGroup(String rcGroupId)
      throws RocketChatGetGroupMembersException, RocketChatUserNotInitializedException {
    return rocketChatGroupClient.getStandardMembersOfGroup(rcGroupId);
  }

  /**
   * Removes all users from the given group except system user and technical user.
   *
   * @param rcGroupId the rocket chat group id
   */
  public void removeAllStandardUsersFromGroup(String rcGroupId)
      throws RocketChatGetGroupMembersException, RocketChatRemoveUserFromGroupException,
          RocketChatUserNotInitializedException {
    rocketChatGroupClient.removeAllStandardUsersFromGroup(rcGroupId);
  }

  @Override
  public Optional<List<Map<String, String>>> findMembers(String chatId) {
    return rocketChatGroupClient.findMembers(chatId);
  }

  /**
   * Get users of a given chat. Replaces getMembersOfGroup due to <a
   * href="https://github.com/RocketChat/Rocket.Chat/issues/25728">Rocket.Chat bug 25728</a>.
   *
   * @param chatId rocket chat id
   * @return all members of the group
   */
  public List<GroupMemberDTO> getChatUsers(String chatId) {
    return rocketChatRoomClient.getChatUsers(chatId);
  }

  /**
   * Returns the group/room members of the given Rocket.Chat group id.
   *
   * @param rcGroupId the rocket chat id
   * @return al members of the group
   * @deprecated use getChatUsers
   */
  @Deprecated
  public List<GroupMemberDTO> getMembersOfGroup(String rcGroupId)
      throws RocketChatGetGroupMembersException {
    return rocketChatGroupClient.getMembersOfGroup(rcGroupId);
  }

  /**
   * Removes all messages from the specified Rocket.Chat group written by the technical user from
   * the last 24 hours (avoiding time zone failures).
   *
   * @param rcGroupId the rocket chat group id
   * @param oldest the oldest message time
   * @param latest the latest message time
   */
  public void removeSystemMessages(String rcGroupId, LocalDateTime oldest, LocalDateTime latest)
      throws RocketChatRemoveSystemMessagesException, RocketChatUserNotInitializedException {
    rocketChatMessageClient.removeSystemMessages(rcGroupId, oldest, latest);
  }

  /**
   * Removes all messages (from every user) from a Rocket.Chat group.
   *
   * @param rcGroupId the rocket chat group id
   */
  public void removeAllMessages(String rcGroupId) throws RocketChatRemoveSystemMessagesException {
    rocketChatMessageClient.removeAllMessages(rcGroupId);
  }

  /**
   * Returns the subscriptions for the given user id.
   *
   * @param rocketChatCredentials {@link RocketChatCredentials}
   * @return the subscriptions of the user
   */
  public List<SubscriptionsUpdateDTO> getSubscriptionsOfUser(
      RocketChatCredentials rocketChatCredentials) {

    ResponseEntity<SubscriptionsGetDTO> response;

    try {
      var header = headersHelper.getStandardHttpHeaders(rocketChatCredentials);
      HttpEntity<Void> request = new HttpEntity<>(header);

      var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.SUBSCRIPTION_GET);
      response = restTemplate.exchange(url, HttpMethod.GET, request, SubscriptionsGetDTO.class);

    } catch (HttpStatusCodeException ex) {
      if (ex.getStatusCode().equals(HttpStatus.UNAUTHORIZED)) {
        throw new RocketChatUnauthorizedException(rocketChatCredentials.getRocketChatUserId(), ex);
      }
      throw new InternalServerErrorException(ex.getMessage(), LogService::logRocketChatError);
    }

    if (response.getStatusCode() == HttpStatus.OK && nonNull(response.getBody())) {
      return asList(response.getBody().getUpdate());
    } else {
      var error = "Could not get Rocket.Chat subscriptions for user id %s";
      throw new InternalServerErrorException(error, LogService::logRocketChatError);
    }
  }

  @Override
  public Optional<List<Map<String, String>>> findAllChats(String chatUserId) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.SUBSCRIPTION_GET);

    try {
      var response = rocketChatClient.getForEntity(url, chatUserId, SubscriptionsGetDTO.class);
      return mapper.mapOfSubscriptionsResponse(response);
    } catch (HttpClientErrorException exception) {
      log.error("Subscriptions Get failed.", exception);
      return Optional.empty();
    }
  }

  @Override
  public boolean updateChatE2eKey(String chatUserId, String roomId, String key) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.GROUP_KEY_UPDATE);
    var updateUser = mapper.updateGroupKeyOf(chatUserId, roomId, key);

    try {
      var response =
          rocketChatClient.postForEntity(url, chatUserId, updateUser, StandardResponseDTO.class);
      return response.getStatusCode().is2xxSuccessful();
    } catch (HttpClientErrorException exception) {
      log.error("Updating E2E group key failed.", exception);
      return false;
    }
  }

  /**
   * Returns the rooms for the given user id.
   *
   * @param rocketChatCredentials {@link RocketChatCredentials}
   * @return the rooms for the user
   */
  public List<RoomsUpdateDTO> getRoomsOfUser(RocketChatCredentials rocketChatCredentials) {
    return rocketChatRoomClient.getRoomsOfUser(rocketChatCredentials);
  }

  /**
   * Returns the information of the given Rocket.Chat user.
   *
   * @param rcUserId Rocket.Chat user id
   * @return the dto containing the user infos
   */
  public UserInfoResponseDTO getUserInfo(String rcUserId) {
    return rocketChatUserClient.getUserInfo(rcUserId);
  }

  /**
   * Deletes the user data of the given Rocket.Chat user.
   *
   * @param rcUserId Rocket.Chat user id
   * @throws RocketChatDeleteUserException when deletion of user fails
   */
  public void deleteUser(String rcUserId) throws RocketChatDeleteUserException {
    rocketChatUserClient.deleteUser(rcUserId);
  }

  /**
   * Sets the Rocket.Chat room with the given id read only.
   *
   * @param rcRoomId the Rocket.Chat room id
   * @throws RocketChatUserNotInitializedException if technical user isn´t initialized
   */
  public void setRoomReadOnly(String rcRoomId) throws RocketChatUserNotInitializedException {
    rocketChatRoomClient.setRoomReadOnly(rcRoomId);
  }

  /**
   * Sets the Rocket.Chat room with the given id writeable.
   *
   * @param rcRoomId the Rocket.Chat room id
   * @throws RocketChatUserNotInitializedException if technical user isn´t initialized
   */
  public void setRoomWriteable(String rcRoomId) throws RocketChatUserNotInitializedException {
    rocketChatRoomClient.setRoomWriteable(rcRoomId);
  }

  /**
   * Returns all private Rocket.Chat groups which are inactive (= no messages written) since given
   * date.
   *
   * @param dateTimeSinceInactive the date and time since when the groups should be inactive
   * @return a {@link List} of {@link GroupDTO} instances
   */
  public List<GroupDTO> fetchAllInactivePrivateGroupsSinceGivenDate(
      LocalDateTime dateTimeSinceInactive) throws RocketChatGetGroupsListAllException {
    return rocketChatGroupClient.fetchAllInactivePrivateGroupsSinceGivenDate(dateTimeSinceInactive);
  }

  /**
   * Returns the id of a Rocket.Chat user by username.
   *
   * @param username the username to search for
   * @return the Rocket.Chat user id
   * @throws RocketChatGetUserIdException when request fails
   */
  public String getRocketChatUserIdByUsername(String username) throws RocketChatGetUserIdException {
    return rocketChatUserClient.getRocketChatUserIdByUsername(username);
  }

  private boolean userWasInRoom(ResponseEntity<MessageResponse> response) {
    var body = response.getBody();
    if (nonNull(body)) {
      var message = body.getMessage();
      return isNull(message) || !message.contains("error-user-not-in-room");
    } else {
      return true;
    }
  }

  public boolean saveRoomSettings(String chatId, boolean encrypted) {
    return rocketChatRoomClient.saveRoomSettings(chatId, encrypted);
  }

  public void removeUserFromGroupIgnoreGroupNotFound(String rcUserId, String rcGroupId)
      throws RocketChatRemoveUserFromGroupException {
    rocketChatGroupClient.removeUserFromGroupIgnoreGroupNotFound(rcUserId, rcGroupId);
  }

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
    return rocketChatUserClient.createUser(username, password, email);
  }
}
