package de.caritas.cob.userservice.api.adapters.rocketchat.client;

import static java.util.Arrays.asList;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentialsProvider;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatEndpoints;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatHttpHeaders;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatMapper;
import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupAddUserBodyDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupCreateBodyDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupDeleteBodyDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupDeleteResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupLeaveBodyDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupMemberDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupMemberResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupRemoveUserBodyDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupsListAllResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatAddUserToGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatCreateGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatDeleteGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatGetGroupMembersException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatGetGroupsListAllException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatLeaveFromGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatRemoveUserFromGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatUserNotInitializedException;
import de.caritas.cob.userservice.api.service.LogService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Client for Rocket.Chat group-related API operations. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RocketChatGroupClient {

  private static final String ERROR_MESSAGE =
      "Error during rollback: Rocket.Chat group with id " + "%s could not be deleted";
  private static final String RC_DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
  private static final String GROUPS_LIST_ALL_ERROR_MESSAGE =
      "Could not get all rocket chat groups";
  private static final Integer PAGE_SIZE = 100;
  private static final String ERROR_ROOM_NOT_FOUND = "error-room-not-found";

  private final @NonNull RestTemplate restTemplate;
  private final @NonNull RocketChatCredentialsProvider rcCredentialHelper;
  private final @NonNull RocketChatConfig rocketChatConfig;
  private final @NonNull RocketChatHttpHeaders headersHelper;
  private final @NonNull RocketChatMapper mapper;
  private final @NonNull RocketChatRoomClient rocketChatRoomClient;

  /**
   * Creation of a private Rocket.Chat group.
   *
   * @param name the group name
   * @param rocketChatCredentials {@link RocketChatCredentials}
   * @return the group id
   */
  public Optional<GroupResponseDTO> createPrivateGroup(
      String name, RocketChatCredentials rocketChatCredentials)
      throws RocketChatCreateGroupException {

    GroupResponseDTO response;

    try {

      var headers = headersHelper.getStandardHttpHeaders(rocketChatCredentials);
      var groupCreateBodyDto = new GroupCreateBodyDTO(name, false);
      HttpEntity<GroupCreateBodyDTO> request = new HttpEntity<>(groupCreateBodyDto, headers);
      var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.GROUP_CREATE);
      response = restTemplate.postForObject(url, request, GroupResponseDTO.class);

    } catch (RestClientResponseException ex) {
      throw new RocketChatCreateGroupException(ex);
    }

    if (!isCreateGroupResponseSuccess(response)) {
      throw new RocketChatCreateGroupException(
          String.format("Rocket.Chat group with name %s could not be created", name));
    }

    return Optional.ofNullable(response);
  }

  private boolean isCreateGroupResponseSuccess(GroupResponseDTO response) {
    return nonNull(response) && response.isSuccess() && isGroupIdAvailable(response);
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

    try {
      RocketChatCredentials systemUserCredentials = rcCredentialHelper.getSystemUser();
      return this.createPrivateGroup(groupName, systemUserCredentials);
    } catch (RocketChatUserNotInitializedException e) {
      throw new RocketChatCreateGroupException(e);
    }
  }

  /**
   * Deletion of a Rocket.Chat group as system user.
   *
   * @param groupId the Rocket.Chat group id
   * @return true, if successfully
   */
  public boolean deleteGroupAsSystemUser(String groupId) {
    try {
      RocketChatCredentials systemUser = rcCredentialHelper.getSystemUser();
      return rollbackGroup(groupId, systemUser);
    } catch (RocketChatUserNotInitializedException e) {
      throw new InternalServerErrorException(e.getMessage(), LogService::logRocketChatError);
    }
  }

  /**
   * Deletion of a Rocket.Chat group as technical user.
   *
   * @param groupId the Rocket.Chat group id
   * @throws RocketChatDeleteGroupException when deletion of group fails
   */
  public void deleteGroupAsTechnicalUser(String groupId) throws RocketChatDeleteGroupException {
    try {
      this.addTechnicalUserToGroup(groupId);
      RocketChatCredentials technicalUser = rcCredentialHelper.getTechnicalUser();
      rollbackGroup(groupId, technicalUser);
    } catch (Exception e) {
      throw new RocketChatDeleteGroupException(e);
    }
  }

  /**
   * Deletion of a Rocket.Chat group.
   *
   * @param groupId the group id
   * @param rocketChatCredentials {@link RocketChatCredentials}
   * @return true, if successfully
   */
  public boolean rollbackGroup(String groupId, RocketChatCredentials rocketChatCredentials) {

    GroupDeleteResponseDTO response = null;

    try {

      var headers = headersHelper.getStandardHttpHeaders(rocketChatCredentials);
      var groupDeleteBodyDto = new GroupDeleteBodyDTO(groupId);
      HttpEntity<GroupDeleteBodyDTO> request = new HttpEntity<>(groupDeleteBodyDto, headers);
      var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.GROUP_DELETE);
      response = restTemplate.postForObject(url, request, GroupDeleteResponseDTO.class);

    } catch (Exception ex) {
      log.error(
          "Rocket.Chat Error: Error during rollback: Rocket.Chat group with id {} could not be "
              + "deleted",
          groupId,
          ex);
    }

    if (response != null && response.isSuccess()) {
      return true;
    } else {
      log.error(
          "Rocket.Chat Error: Error during rollback: Rocket.Chat group with id {} could not be "
              + "deleted (Error: unknown / ErrorType: unknown)",
          groupId);

      return false;
    }
  }

  private boolean isGroupIdAvailable(GroupResponseDTO response) {
    return nonNull(response.getGroup()) && nonNull(response.getGroup().getId());
  }

  /**
   * Adds the provided user to the Rocket.Chat group with given groupId.
   *
   * @param rcUserId Rocket.Chat userId
   * @param rcGroupId Rocket.Chat roomId
   */
  public void addUserToGroup(String rcUserId, String rcGroupId)
      throws RocketChatAddUserToGroupException {

    GroupResponseDTO response;
    try {
      RocketChatCredentials technicalUser = rcCredentialHelper.getTechnicalUser();
      var header = headersHelper.getStandardHttpHeaders(technicalUser);
      var body = new GroupAddUserBodyDTO(rcUserId, rcGroupId);
      HttpEntity<GroupAddUserBodyDTO> request = new HttpEntity<>(body, header);

      var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.GROUP_INVITE);
      response = restTemplate.postForObject(url, request, GroupResponseDTO.class);

    } catch (Exception ex) {
      log.error(
          "Rocket.Chat Error: Could not add user {} to Rocket.Chat group with id {}. Reason: ",
          rcUserId,
          rcGroupId,
          ex);
      throw new RocketChatAddUserToGroupException(
          String.format(
              "Could not add user %s to Rocket.Chat group with id %s", rcUserId, rcGroupId));
    }

    if (nonNull(response) && !response.isSuccess()) {
      var error = "Could not add user %s to Rocket.Chat group with id %s";
      throw new RocketChatAddUserToGroupException(String.format(error, rcUserId, rcGroupId));
    }
  }

  /**
   * Adds the technical user to the given Rocket.Chat group id.
   *
   * @param rcGroupId the rocket chat group id
   */
  public void addTechnicalUserToGroup(String rcGroupId)
      throws RocketChatAddUserToGroupException, RocketChatUserNotInitializedException {
    this.addUserToGroup(rcCredentialHelper.getTechnicalUser().getRocketChatUserId(), rcGroupId);
  }

  /**
   * Leave from the Rocket.Chat group with given groupId as the technical user.
   *
   * @param rcGroupId Rocket.Chat roomId
   * @throws RocketChatLeaveFromGroupException on failure
   */
  public void leaveFromGroupAsTechnicalUser(String rcGroupId)
      throws RocketChatLeaveFromGroupException {

    GroupResponseDTO response;
    try {
      var technicalUser = rcCredentialHelper.getTechnicalUser();
      var header = headersHelper.getStandardHttpHeaders(technicalUser);
      var body = new GroupLeaveBodyDTO(rcGroupId);
      HttpEntity<GroupLeaveBodyDTO> request = new HttpEntity<>(body, header);

      var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.ROOM_LEAVE);
      response = restTemplate.postForObject(url, request, GroupResponseDTO.class);

    } catch (Exception ex) {
      log.error(
          "Rocket.Chat Error: Could not leave as technical user from Rocket.Chat group with id {}. Reason: ",
          rcGroupId,
          ex);
      throw new RocketChatLeaveFromGroupException(
          String.format(
              "Could not leave as technical user from Rocket.Chat group with id %s", rcGroupId));
    }

    if (response != null && !response.isSuccess()) {
      var error = "Could not leave as technical user from Rocket.Chat group with id %s";
      throw new RocketChatLeaveFromGroupException(String.format(error, rcGroupId));
    }
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

    GroupResponseDTO response;
    try {
      response = tryRemoveUserFromGroup(rcUserId, rcGroupId);

    } catch (Exception ex) {
      log.error(
          "Rocket.Chat Error: Could not remove user {} from Rocket.Chat group with id {}. Reason: ",
          rcUserId,
          rcGroupId,
          ex);
      throw new RocketChatRemoveUserFromGroupException(
          String.format(
              "Could not remove user %s from Rocket.Chat group with id %s", rcUserId, rcGroupId));
    }

    if (response != null && !response.isSuccess()) {
      var error = "Could not remove user %s from Rocket.Chat group with id %s";
      throw new RocketChatRemoveUserFromGroupException(String.format(error, rcUserId, rcGroupId));
    }
  }

  private GroupResponseDTO tryRemoveUserFromGroup(String rcUserId, String rcGroupId)
      throws RocketChatUserNotInitializedException {
    GroupResponseDTO response;
    RocketChatCredentials technicalUser = rcCredentialHelper.getTechnicalUser();
    var header = headersHelper.getStandardHttpHeaders(technicalUser);
    var body = new GroupRemoveUserBodyDTO(rcUserId, rcGroupId);
    HttpEntity<GroupRemoveUserBodyDTO> request = new HttpEntity<>(body, header);

    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.GROUP_KICK);
    response = restTemplate.postForObject(url, request, GroupResponseDTO.class);
    return response;
  }

  /**
   * Removes the provided user from the Rocket.Chat group with given groupId, ignoring
   * group-not-found errors.
   *
   * @param rcUserId Rocket.Chat userId
   * @param rcGroupId Rocket.Chat roomId
   * @throws RocketChatRemoveUserFromGroupException on failure
   */
  public void removeUserFromGroupIgnoreGroupNotFound(String rcUserId, String rcGroupId)
      throws RocketChatRemoveUserFromGroupException {
    GroupResponseDTO response;
    try {
      response = tryRemoveUserFromGroup(rcUserId, rcGroupId);
    } catch (Exception ex) {
      if (ex.getMessage() != null && ex.getMessage().contains(ERROR_ROOM_NOT_FOUND)) {
        return;
      }
      log.error(
          "Rocket.Chat Error: Could not remove user {} from Rocket.Chat group with id {}. Reason: ",
          rcUserId,
          rcGroupId,
          ex);
      throw new RocketChatRemoveUserFromGroupException(
          String.format(
              "Could not remove user %s from Rocket.Chat group with id %s", rcUserId, rcGroupId));
    }

    if (response != null && !response.isSuccess()) {
      var error = "Could not remove user %s from Rocket.Chat group with id %s";
      throw new RocketChatRemoveUserFromGroupException(String.format(error, rcUserId, rcGroupId));
    }
  }

  /**
   * Removes a user from a session (group) by adding the technical user, kicking the user, and
   * leaving as technical user.
   *
   * @param chatUserId the chat user id to remove
   * @param chatId the chat id
   * @return true if successful
   */
  public boolean removeUserFromSession(String chatUserId, String chatId) {
    try {
      addTechnicalUserToGroup(chatId);
      removeUserFromGroup(chatUserId, chatId);
      leaveFromGroupAsTechnicalUser(chatId);

      return true;
    } catch (Exception exception) {
      log.error("error", exception);

      return false;
    }
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

    List<GroupMemberDTO> groupMemberList;
    try {
      groupMemberList = rocketChatRoomClient.getChatUsers(rcGroupId);
    } catch (Exception exception) {
      log.error("Could not get chat users. Reason: ", exception);
      throw new RocketChatGetGroupMembersException(
          String.format("Group member list from group with id %s is empty", rcGroupId));
    }

    Iterator<GroupMemberDTO> groupMemberListIterator = groupMemberList.iterator();
    while (groupMemberListIterator.hasNext()) {
      var groupMemberDTO = groupMemberListIterator.next();
      if (groupMemberDTO
              .get_id()
              .equals(rcCredentialHelper.getTechnicalUser().getRocketChatUserId())
          || groupMemberDTO
              .get_id()
              .equals(rcCredentialHelper.getSystemUser().getRocketChatUserId())) {
        groupMemberListIterator.remove();
      }
    }

    return groupMemberList;
  }

  /**
   * Removes all users from the given group except system user and technical user.
   *
   * @param rcGroupId the rocket chat group id
   */
  public void removeAllStandardUsersFromGroup(String rcGroupId)
      throws RocketChatGetGroupMembersException, RocketChatRemoveUserFromGroupException,
          RocketChatUserNotInitializedException {
    List<GroupMemberDTO> groupMemberList = rocketChatRoomClient.getChatUsers(rcGroupId);

    if (groupMemberList.isEmpty()) {
      throw new RocketChatGetGroupMembersException(
          String.format("Group member list from group with id %s is empty", rcGroupId));
    }

    for (GroupMemberDTO member : groupMemberList) {
      if (!member.get_id().equals(rcCredentialHelper.getTechnicalUser().getRocketChatUserId())
          && !member.get_id().equals(rcCredentialHelper.getSystemUser().getRocketChatUserId())) {
        removeUserFromGroup(member.get_id(), rcGroupId);
      }
    }
  }

  /**
   * Finds the members of a given chat.
   *
   * @param chatId the chat id
   * @return an optional list of member maps
   */
  public Optional<List<Map<String, String>>> findMembers(String chatId) {
    var members = rocketChatRoomClient.getChatUsers(chatId);
    var memberMaps = mapper.mapOf(members);

    return Optional.of(memberMaps);
  }

  /**
   * Returns the group/room members of the given Rocket.Chat group id.
   *
   * @param rcGroupId the rocket chat id
   * @return all members of the group
   * @deprecated use getChatUsers
   */
  @Deprecated
  public List<GroupMemberDTO> getMembersOfGroup(String rcGroupId)
      throws RocketChatGetGroupMembersException {

    ResponseEntity<GroupMemberResponseDTO> response;
    try {
      RocketChatCredentials systemUser = rcCredentialHelper.getSystemUser();
      var header = headersHelper.getStandardHttpHeaders(systemUser);
      HttpEntity<GroupAddUserBodyDTO> request = new HttpEntity<>(header);

      response =
          restTemplate.exchange(
              buildGetGroupMembersPath(rcGroupId),
              HttpMethod.GET,
              request,
              GroupMemberResponseDTO.class);

    } catch (Exception ex) {
      log.error("Could not get chat users. Reason: ", ex);
      throw new RocketChatGetGroupMembersException(
          String.format("Could not get Rocket.Chat group" + " members for room id %s", rcGroupId),
          ex);
    }

    if (response.getStatusCode() == HttpStatus.OK && nonNull(response.getBody())) {
      return asList(response.getBody().getMembers());
    } else {
      var error = "Could not get Rocket.Chat group members for room id %s";
      throw new RocketChatGetGroupMembersException(String.format(error, rcGroupId));
    }
  }

  private String buildGetGroupMembersPath(String rcGroupId) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.GROUP_MEMBERS);

    return UriComponentsBuilder.fromUriString(url)
        .queryParam("roomId", rcGroupId)
        .queryParam("count", 0)
        .build()
        .encode()
        .toUriString();
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

    String filter =
        String.format(
            "{\"lm\": {\"$lt\": {\"$date\": \"%s\"}}, \"$and\": [{\"t\": \"p\"}]}",
            dateTimeSinceInactive.format(DateTimeFormatter.ofPattern(RC_DATE_TIME_PATTERN)));
    return getGroupsListAll(filter);
  }

  /**
   * Returns a list of all Rocket.Chat groups.
   *
   * @param mongoDbQuery mongoDB Query as {@link String}
   * @return a {@link List} of {@link GroupDTO} instances
   * @throws RocketChatGetGroupsListAllException when request fails
   */
  private List<GroupDTO> getGroupsListAll(String mongoDbQuery)
      throws RocketChatGetGroupsListAllException {

    try {
      var technicalUser = rcCredentialHelper.getTechnicalUser();
      var header = headersHelper.getStandardHttpHeaders(technicalUser);
      HttpEntity<GroupAddUserBodyDTO> request = new HttpEntity<>(header);

      return getGroupListAllCombiningPages(mongoDbQuery, request);
    } catch (Exception ex) {
      log.error("Rocket.Chat Error: Could not get Rocket.Chat groups list all. Reason: ", ex);
      throw new RocketChatGetGroupsListAllException(GROUPS_LIST_ALL_ERROR_MESSAGE, ex);
    }
  }

  private List<GroupDTO> getGroupListAllCombiningPages(
      String mongoDbQuery, HttpEntity<GroupAddUserBodyDTO> request)
      throws RocketChatGetGroupsListAllException {
    List<GroupDTO> result = Lists.newArrayList();
    int currentOffset = 0;
    var pageResponse =
        getGroupsListAllResponseDTOResponseEntityForCurrentOffset(
            mongoDbQuery, request, currentOffset);

    var totalResultSize = 0;
    if (isResponseSuccessful(pageResponse)) {
      totalResultSize = pageResponse.getBody().getTotal();
    }

    while (isResponseSuccessful(pageResponse) && currentOffset < totalResultSize) {
      result.addAll(asList(pageResponse.getBody().getGroups()));
      currentOffset += PAGE_SIZE;
      pageResponse =
          getGroupsListAllResponseDTOResponseEntityForCurrentOffset(
              mongoDbQuery, request, currentOffset);
    }
    if (pageResponse.getStatusCode() != HttpStatus.OK || isNull(pageResponse.getBody())) {
      log.error(
          "Could not get Rocket.Chat groups list all. Reason {} {}. Url {}",
          pageResponse.getStatusCode(),
          pageResponse.getBody(),
          getGroupAllPaginatedUrl(currentOffset));
      throw new RocketChatGetGroupsListAllException(GROUPS_LIST_ALL_ERROR_MESSAGE);
    }

    return result;
  }

  private boolean isResponseSuccessful(ResponseEntity<GroupsListAllResponseDTO> pageResponse) {
    return pageResponse.getStatusCode() == HttpStatus.OK && nonNull(pageResponse.getBody());
  }

  private ResponseEntity<GroupsListAllResponseDTO>
      getGroupsListAllResponseDTOResponseEntityForCurrentOffset(
          String mongoDbQuery, HttpEntity<GroupAddUserBodyDTO> request, int currentOffset) {
    ResponseEntity<GroupsListAllResponseDTO> response;
    var url = getGroupAllPaginatedUrl(currentOffset);
    response =
        restTemplate.exchange(
            url, HttpMethod.GET, request, GroupsListAllResponseDTO.class, mongoDbQuery);
    return response;
  }

  private String getGroupAllPaginatedUrl(int currentOffset) {
    return rocketChatConfig.getApiUrl(RocketChatEndpoints.GROUP_LIST)
        + "?query={query}&offset="
        + currentOffset
        + "&count="
        + PAGE_SIZE;
  }
}
