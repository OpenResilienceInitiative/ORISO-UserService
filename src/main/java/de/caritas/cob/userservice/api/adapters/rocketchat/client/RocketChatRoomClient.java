package de.caritas.cob.userservice.api.adapters.rocketchat.client;

import static com.mongodb.client.model.Filters.eq;
import static java.util.Arrays.asList;
import static java.util.Objects.nonNull;

import com.mongodb.client.MongoClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentialsProvider;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatEndpoints;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatHttpHeaders;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatMapper;
import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupMemberDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.message.MessageResponse;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomResponse;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomsGetDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomsUpdateDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.SetRoomReadOnlyBodyDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatUserNotInitializedException;
import de.caritas.cob.userservice.api.service.LogService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/** Client for Rocket.Chat room-related API operations. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RocketChatRoomClient {

  private static final String MONGO_DATABASE_NAME = "rocketchat";
  private static final String MONGO_COLLECTION_SUBSCRIPTION = "rocketchat_subscription";
  private static final String CHAT_ROOM_ERROR_MESSAGE =
      "Could not get Rocket.Chat rooms for user id %s";

  private final @NonNull RestTemplate restTemplate;
  private final @NonNull RocketChatCredentialsProvider rcCredentialHelper;
  private final @NonNull RocketChatConfig rocketChatConfig;
  private final @NonNull RocketChatHttpHeaders headersHelper;
  private final @NonNull RocketChatClient rocketChatClient;
  private final @NonNull MongoClient mongoClient;
  private final @NonNull RocketChatMapper mapper;

  /**
   * Returns the rooms for the given user id.
   *
   * @param rocketChatCredentials {@link RocketChatCredentials}
   * @return the rooms for the user
   */
  public List<RoomsUpdateDTO> getRoomsOfUser(RocketChatCredentials rocketChatCredentials) {

    ResponseEntity<RoomsGetDTO> response;

    try {
      var header = headersHelper.getStandardHttpHeaders(rocketChatCredentials);
      HttpEntity<Void> request = new HttpEntity<>(header);
      var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.ROOM_GET);
      response = restTemplate.exchange(url, HttpMethod.GET, request, RoomsGetDTO.class);

    } catch (Exception ex) {
      throw new InternalServerErrorException(
          String.format(CHAT_ROOM_ERROR_MESSAGE, rocketChatCredentials.getRocketChatUserId()),
          ex,
          LogService::logRocketChatError);
    }

    if (response.getStatusCode() == HttpStatus.OK && nonNull(response.getBody())) {
      return asList(response.getBody().getUpdate());
    } else {
      var error =
          String.format(CHAT_ROOM_ERROR_MESSAGE, rocketChatCredentials.getRocketChatUserId());
      throw new InternalServerErrorException(error, LogService::logRocketChatError);
    }
  }

  /**
   * Returns the information of the given Rocket.Chat room.
   *
   * @param roomId the Rocket.Chat room id
   * @return an {@link Optional} of the room info
   */
  public Optional<Map<String, Object>> getChatInfo(String roomId) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.ROOM_INFO + roomId);

    try {
      var response = rocketChatClient.getForEntity(url, RoomResponse.class);
      return mapper.mapOfRoomResponse(response);
    } catch (HttpClientErrorException exception) {
      log.error("Chat Info failed.", exception);
      return Optional.empty();
    }
  }

  /**
   * Get users of a given chat directly from MongoDB. Replaces getMembersOfGroup due to <a
   * href="https://github.com/RocketChat/Rocket.Chat/issues/25728">Rocket.Chat bug 25728</a>.
   *
   * @param chatId rocket chat id
   * @return all members of the group
   */
  public List<GroupMemberDTO> getChatUsers(String chatId) {
    var subscriptions =
        mongoClient
            .getDatabase(MONGO_DATABASE_NAME)
            .getCollection(MONGO_COLLECTION_SUBSCRIPTION)
            .find(eq("rid", chatId));

    var members = new ArrayList<GroupMemberDTO>();
    try (var cursor = subscriptions.iterator()) {
      while (cursor.hasNext()) {
        var subscription = cursor.next();
        var member = new GroupMemberDTO();
        var user = (Document) subscription.get("u");
        member.set_id(user.getString("_id"));
        member.setName(user.getString("name"));
        member.setUsername(user.getString("username"));
        members.add(member);
      }
    }

    return members;
  }

  /**
   * Sets the Rocket.Chat room with the given id read only.
   *
   * @param rcRoomId the Rocket.Chat room id
   * @throws RocketChatUserNotInitializedException if technical user isn't initialized
   */
  public void setRoomReadOnly(String rcRoomId) throws RocketChatUserNotInitializedException {
    setRoomState(rcRoomId, true);
  }

  /**
   * Sets the Rocket.Chat room with the given id writeable.
   *
   * @param rcRoomId the Rocket.Chat room id
   * @throws RocketChatUserNotInitializedException if technical user isn't initialized
   */
  public void setRoomWriteable(String rcRoomId) throws RocketChatUserNotInitializedException {
    setRoomState(rcRoomId, false);
  }

  /**
   * Saves room settings for the given chat.
   *
   * @param chatId the chat id
   * @param encrypted whether the room should be encrypted
   * @return true if successful
   */
  public boolean saveRoomSettings(String chatId, boolean encrypted) {
    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.ROOM_SAVE_SETTINGS);
    var mapOfRoomSettings = mapper.mapOfRoomSettings(chatId, encrypted);

    try {
      var response = rocketChatClient.postForEntity(url, mapOfRoomSettings, MessageResponse.class);
      return response.getStatusCode().is2xxSuccessful();
    } catch (HttpClientErrorException exception) {
      log.error("Saving room settings failed.", exception);
      return false;
    }
  }

  private void setRoomState(String rcRoomId, boolean readOnly)
      throws RocketChatUserNotInitializedException {
    var requestDTO = new SetRoomReadOnlyBodyDTO(rcRoomId, readOnly);
    RocketChatCredentials systemUser = rcCredentialHelper.getSystemUser();
    var header = headersHelper.getStandardHttpHeaders(systemUser);
    HttpEntity<SetRoomReadOnlyBodyDTO> request = new HttpEntity<>(requestDTO, header);

    var url = rocketChatConfig.getApiUrl(RocketChatEndpoints.GROUP_READ_ONLY);
    var response = restTemplate.exchange(url, HttpMethod.POST, request, GroupResponseDTO.class);

    GroupResponseDTO responseBody = response.getBody();
    if (nonNull(responseBody) && !responseBody.isSuccess()) {
      log.error(
          "Rocket.Chat Error: Mark group with id {} as read only failed with reason {}",
          rcRoomId,
          responseBody.getError());
    }
  }
}
