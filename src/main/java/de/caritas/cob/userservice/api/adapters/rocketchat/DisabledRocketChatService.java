package de.caritas.cob.userservice.api.adapters.rocketchat;

import static java.util.Collections.emptyList;

import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.StandardResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupMemberDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.LoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomsUpdateDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.subscriptions.SubscriptionsUpdateDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.RocketChatUserDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UserInfoResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UserUpdateRequestDTO;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatLoginException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Inert Rocket.Chat adapter bound when {@code rocket-chat.enabled=false} (the default, ADR-004).
 *
 * <p>Every operation is a no-op: read paths return empty results and never throw, write paths log a
 * warning once per operation (data that would have gone to Rocket.Chat is intentionally dropped —
 * Matrix is the only chat backend). Login-style operations that cannot fake a usable result throw
 * the declared {@link RocketChatLoginException}, which all callers already handle.
 *
 * <p>No Rocket.Chat REST call, MongoDB connection or credential cron ever happens: the overridden
 * {@link #updateCredentials()} carries neither {@code @PostConstruct} nor {@code @Scheduled}
 * semantics at runtime because scheduling only considers the most specific method declaration.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rocket-chat.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledRocketChatService extends RocketChatService {

  private static final String DISABLED_MESSAGE =
      "Rocket.Chat is disabled (rocket-chat.enabled=false)";

  private final Set<String> warnedOperations = ConcurrentHashMap.newKeySet();

  public DisabledRocketChatService(
      RocketChatCredentialsProvider rcCredentialHelper,
      RocketChatConfig rocketChatConfig,
      RocketChatMapper mapper) {
    super(new RestTemplate(), rcCredentialHelper, null, null, rocketChatConfig, mapper, null);
  }

  private void skip(String operation) {
    log.debug("{} - skipping {}", DISABLED_MESSAGE, operation);
  }

  private void warnOnce(String operation) {
    if (warnedOperations.add(operation)) {
      log.warn(
          "{} - {} is a no-op; nothing is written to Rocket.Chat", DISABLED_MESSAGE, operation);
    } else {
      skip(operation);
    }
  }

  @Override
  public void updateCredentials() {
    skip("credential rotation");
  }

  @Override
  public boolean muteUserInChat(String username, String roomId) {
    skip("muteUserInChat");
    return true;
  }

  @Override
  public boolean unmuteUserInChat(String username, String roomId) {
    skip("unmuteUserInChat");
    return true;
  }

  @Override
  public UserInfoResponseDTO updateUser(UserUpdateRequestDTO requestDTO) {
    warnOnce("updateUser");
    return emptyUserInfo(requestDTO != null ? requestDTO.getUserId() : null);
  }

  @Override
  public boolean updateUser(String chatUserId, String displayName) {
    warnOnce("updateUser(displayName)");
    return true;
  }

  @Override
  public Set<String> findAllAvailableUserIds() {
    return Set.of();
  }

  @Override
  public Optional<Boolean> isLoggedIn(String chatUserId) {
    return Optional.empty();
  }

  @Override
  public Optional<Boolean> isAvailable(String chatUserId) {
    return Optional.empty();
  }

  @Override
  public boolean setUserPresence(String username, String status) {
    skip("setUserPresence");
    return true;
  }

  @Override
  public Optional<Map<String, Object>> findUser(String chatUserId) {
    return Optional.empty();
  }

  @Override
  public Optional<Map<String, Object>> findUserAndAddToCache(String chatUserId) {
    return Optional.empty();
  }

  @Override
  public Optional<Map<String, Object>> getChatInfo(String roomId) {
    return Optional.empty();
  }

  @Override
  public Optional<GroupResponseDTO> createPrivateGroup(
      String name, RocketChatCredentials rocketChatCredentials) {
    warnOnce("createPrivateGroup");
    return Optional.empty();
  }

  @Override
  public Optional<GroupResponseDTO> createPrivateGroupWithSystemUser(String groupName) {
    warnOnce("createPrivateGroupWithSystemUser");
    return Optional.empty();
  }

  @Override
  public boolean deleteGroupAsSystemUser(String groupId) {
    skip("deleteGroupAsSystemUser");
    return true;
  }

  @Override
  public void deleteGroupAsTechnicalUser(String groupId) {
    skip("deleteGroupAsTechnicalUser");
  }

  @Override
  public boolean rollbackGroup(String groupId, RocketChatCredentials rocketChatCredentials) {
    skip("rollbackGroup");
    return true;
  }

  @Override
  public String getUserID(String username, String password, boolean firstLogin)
      throws RocketChatLoginException {
    warnOnce("getUserID");
    throw new RocketChatLoginException(DISABLED_MESSAGE);
  }

  @Override
  public ResponseEntity<LoginResponseDTO> loginWithPassword(String username, String password)
      throws RocketChatLoginException {
    warnOnce("loginWithPassword");
    throw new RocketChatLoginException(DISABLED_MESSAGE);
  }

  @Override
  public ResponseEntity<LoginResponseDTO> loginUserFirstTime(String username, String password)
      throws RocketChatLoginException {
    warnOnce("loginUserFirstTime");
    throw new RocketChatLoginException(DISABLED_MESSAGE);
  }

  @Override
  public boolean logoutUser(RocketChatCredentials rocketChatCredentials) {
    skip("logoutUser");
    return true;
  }

  @Override
  public void addUserToGroup(String rcUserId, String rcGroupId) {
    skip("addUserToGroup");
  }

  @Override
  public void addTechnicalUserToGroup(String rcGroupId) {
    skip("addTechnicalUserToGroup");
  }

  @Override
  public void leaveFromGroupAsTechnicalUser(String rcGroupId) {
    skip("leaveFromGroupAsTechnicalUser");
  }

  @Override
  public void removeUserFromGroup(String rcUserId, String rcGroupId) {
    skip("removeUserFromGroup");
  }

  @Override
  public boolean removeUserFromSession(String chatUserId, String chatId) {
    skip("removeUserFromSession");
    return true;
  }

  @Override
  public List<GroupMemberDTO> getStandardMembersOfGroup(String rcGroupId) {
    return emptyList();
  }

  @Override
  public void removeAllStandardUsersFromGroup(String rcGroupId) {
    skip("removeAllStandardUsersFromGroup");
  }

  @Override
  public Optional<List<Map<String, String>>> findMembers(String chatId) {
    return Optional.empty();
  }

  @Override
  public List<GroupMemberDTO> getChatUsers(String chatId) {
    return emptyList();
  }

  @Override
  public List<GroupMemberDTO> getMembersOfGroup(String rcGroupId) {
    return emptyList();
  }

  @Override
  public void removeSystemMessages(String rcGroupId, LocalDateTime oldest, LocalDateTime latest) {
    skip("removeSystemMessages");
  }

  @Override
  public void removeAllMessages(String rcGroupId) {
    skip("removeAllMessages");
  }

  @Override
  public List<SubscriptionsUpdateDTO> getSubscriptionsOfUser(
      RocketChatCredentials rocketChatCredentials) {
    return emptyList();
  }

  @Override
  public Optional<List<Map<String, String>>> findAllChats(String chatUserId) {
    return Optional.empty();
  }

  @Override
  public boolean updateChatE2eKey(String chatUserId, String roomId, String key) {
    warnOnce("updateChatE2eKey");
    return true;
  }

  @Override
  public List<RoomsUpdateDTO> getRoomsOfUser(RocketChatCredentials rocketChatCredentials) {
    return emptyList();
  }

  @Override
  public UserInfoResponseDTO getUserInfo(String rcUserId) {
    return emptyUserInfo(rcUserId);
  }

  @Override
  public void deleteUser(String rcUserId) {
    skip("deleteUser");
  }

  @Override
  public void setRoomReadOnly(String rcRoomId) {
    skip("setRoomReadOnly");
  }

  @Override
  public void setRoomWriteable(String rcRoomId) {
    skip("setRoomWriteable");
  }

  @Override
  public List<GroupDTO> fetchAllInactivePrivateGroupsSinceGivenDate(
      LocalDateTime dateTimeSinceInactive) {
    return emptyList();
  }

  @Override
  public String getRocketChatUserIdByUsername(String username) {
    skip("getRocketChatUserIdByUsername");
    return null;
  }

  @Override
  public boolean saveRoomSettings(String chatId, boolean encrypted) {
    skip("saveRoomSettings");
    return true;
  }

  @Override
  public void removeUserFromGroupIgnoreGroupNotFound(String rcUserId, String rcGroupId) {
    skip("removeUserFromGroupIgnoreGroupNotFound");
  }

  @Override
  public ResponseEntity<StandardResponseDTO> createUser(
      String username, String password, String email) throws RocketChatLoginException {
    warnOnce("createUser");
    throw new RocketChatLoginException(DISABLED_MESSAGE);
  }

  private static UserInfoResponseDTO emptyUserInfo(String rcUserId) {
    var user = new RocketChatUserDTO();
    user.setId(rcUserId);
    user.setRooms(emptyList());
    return new UserInfoResponseDTO(user, true, null, null);
  }
}
