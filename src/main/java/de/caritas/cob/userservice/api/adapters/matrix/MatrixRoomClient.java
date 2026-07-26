package de.caritas.cob.userservice.api.adapters.matrix;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserResponseDTO;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatrixRoomClient {

  private static final String ENDPOINT_CREATE_ROOM = "/_matrix/client/r0/createRoom";
  private static final String ROOM_ENCRYPTION_EVENT_TYPE = "m.room.encryption";
  private static final String MEGOLM_ALGORITHM = "m.megolm.v1.aes-sha2";
  private static final String ENDPOINT_INVITE_USER = "/_matrix/client/r0/rooms/{roomId}/invite";
  private static final String ENDPOINT_JOIN_ROOM = "/_matrix/client/r0/rooms/{roomId}/join";
  private static final String ENDPOINT_LEAVE_ROOM = "/_matrix/client/r0/rooms/{roomId}/leave";
  private static final String ENDPOINT_BAN_ROOM = "/_matrix/client/r0/rooms/{roomId}/ban";
  private static final String ENDPOINT_UNBAN_ROOM = "/_matrix/client/r0/rooms/{roomId}/unban";
  private static final String ENDPOINT_POWER_LEVELS =
      "/_matrix/client/r0/rooms/{roomId}/state/m.room.power_levels";
  private static final String ENDPOINT_MEMBERSHIP =
      "/_matrix/client/r0/rooms/{roomId}/state/m.room.member/{userId}";

  private final MatrixConfig matrixConfig;
  private final RestTemplate restTemplate;

  public ResponseEntity<MatrixCreateRoomResponseDTO> createRoom(
      String roomName, String roomAlias, String accessToken) throws MatrixCreateRoomException {

    return createRoom(roomName, roomAlias, accessToken, false);
  }

  public ResponseEntity<MatrixCreateRoomResponseDTO> createRoom(
      String roomName, String roomAlias, String accessToken, boolean encryptionEnabled)
      throws MatrixCreateRoomException {

    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      var roomCreateRequest = new MatrixCreateRoomRequestDTO();
      roomCreateRequest.setName(roomName);
      roomCreateRequest.setRoomAliasName(roomAlias);
      roomCreateRequest.setPreset("private_chat");
      roomCreateRequest.setVisibility("private");

      roomCreateRequest.setInitialState(buildInitialState(encryptionEnabled));

      HttpEntity<MatrixCreateRoomRequestDTO> request = new HttpEntity<>(roomCreateRequest, headers);

      var url = buildUrl(ENDPOINT_CREATE_ROOM);
      log.info("Creating Matrix room: {} at URL: {}", roomName, url);

      var response = restTemplate.postForEntity(url, request, MatrixCreateRoomResponseDTO.class);

      if (nonNull(response.getBody()) && nonNull(response.getBody().getRoomId())) {
        log.info(
            "Successfully created Matrix room: {} with ID: {}",
            roomName,
            response.getBody().getRoomId());
      }

      return response;
    } catch (HttpClientErrorException ex) {
      log.error(
          "Matrix Error: Could not create room ({}) in Matrix. Status: {}, Response: {}",
          roomName,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      throw new MatrixCreateRoomException(
          String.format(
              "Could not create room (%s) in Matrix: %s", roomName, ex.getResponseBodyAsString()));
    } catch (Exception ex) {
      log.error("Matrix Error: Could not create room ({}) in Matrix. Reason", roomName, ex);
      throw new MatrixCreateRoomException(
          String.format("Could not create room (%s) in Matrix", roomName));
    }
  }

  private java.util.List<MatrixCreateRoomRequestDTO.InitialStateEvent> buildInitialState(
      boolean encryptionEnabled) {
    if (!encryptionEnabled) {
      return java.util.List.of();
    }

    var encryptionEvent = new MatrixCreateRoomRequestDTO.InitialStateEvent();
    encryptionEvent.setType(ROOM_ENCRYPTION_EVENT_TYPE);
    encryptionEvent.setStateKey("");
    encryptionEvent.setContent(Map.of("algorithm", MEGOLM_ALGORITHM));
    return java.util.List.of(encryptionEvent);
  }

  public ResponseEntity<MatrixInviteUserResponseDTO> inviteUserToRoom(
      String roomId, String userId, String accessToken) throws MatrixInviteUserException {

    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      var inviteRequest = new MatrixInviteUserRequestDTO();
      inviteRequest.setUserId(userId);

      HttpEntity<MatrixInviteUserRequestDTO> request = new HttpEntity<>(inviteRequest, headers);

      var url = buildUrl(ENDPOINT_INVITE_USER, Map.of("roomId", roomId));
      log.info("Inviting Matrix user: {} to room: {} at URL: {}", userId, roomId, url);

      var response = restTemplate.postForEntity(url, request, MatrixInviteUserResponseDTO.class);

      log.info("Successfully invited Matrix user: {} to room: {}", userId, roomId);

      return response;
    } catch (HttpClientErrorException ex) {
      log.error(
          "Matrix Error: Could not invite user ({}) to room ({}) in Matrix. Status: {}, Response: {}",
          userId,
          roomId,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      throw new MatrixInviteUserException(
          String.format(
              "Could not invite user (%s) to room (%s) in Matrix: %s",
              userId, roomId, ex.getResponseBodyAsString()));
    } catch (Exception ex) {
      log.error(
          "Matrix Error: Could not invite user ({}) to room ({}) in Matrix. Reason",
          userId,
          roomId,
          ex);
      throw new MatrixInviteUserException(
          String.format("Could not invite user (%s) to room (%s) in Matrix", userId, roomId));
    }
  }

  public boolean joinRoom(String roomId, String accessToken) {
    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      HttpEntity<String> request = new HttpEntity<>("{}", headers);

      var url = buildUrl(ENDPOINT_JOIN_ROOM, Map.of("roomId", roomId));
      log.info("Accepting room invitation (joining room): {} at URL: {}", roomId, url);

      var response = restTemplate.postForEntity(url, request, Map.class);

      if (response.getStatusCode().is2xxSuccessful()) {
        log.info("Successfully joined Matrix room: {}", roomId);
        return true;
      } else {
        log.warn("Failed to join Matrix room: {}. Status: {}", roomId, response.getStatusCode());
        return false;
      }
    } catch (HttpClientErrorException ex) {
      if (ex.getStatusCode().value() == 403
          && ex.getResponseBodyAsString().contains("already in the room")) {
        log.info("User already in Matrix room: {}, skipping join", roomId);
        return true;
      }
      log.error(
          "Matrix Error: Could not join room ({}). Status: {}, Response: {}",
          roomId,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      return false;
    } catch (Exception ex) {
      log.error("Matrix Error: Could not join room ({}). Reason: {}", roomId, ex.getMessage());
      return false;
    }
  }

  /**
   * Leaves a Matrix room with the given user's own access token (the canonical self-leave, {@code
   * POST /rooms/{roomId}/leave}).
   *
   * <p>Best-effort: never throws. Leaving a room the user is not (or no longer) a member of is
   * treated as success, because the desired end state ("user is not in the room") already holds.
   *
   * @param roomId the Matrix room ID
   * @param accessToken the access token of the leaving user
   * @return true when the user is not in the room afterwards, false when the leave failed
   */
  public boolean leaveRoom(String roomId, String accessToken) {
    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      HttpEntity<String> request = new HttpEntity<>("{}", headers);

      var url = buildUrl(ENDPOINT_LEAVE_ROOM, Map.of("roomId", roomId));
      log.info("Leaving Matrix room: {} at URL: {}", roomId, url);

      var response = restTemplate.postForEntity(url, request, Map.class);

      if (response.getStatusCode().is2xxSuccessful()) {
        log.info("Successfully left Matrix room: {}", roomId);
        return true;
      }
      log.warn("Failed to leave Matrix room: {}. Status: {}", roomId, response.getStatusCode());
      return false;
    } catch (HttpClientErrorException ex) {
      if (ex.getStatusCode().value() == 403 || ex.getStatusCode().value() == 404) {
        log.info(
            "User was not in Matrix room {} (status {}); nothing to leave",
            roomId,
            ex.getStatusCode());
        return true;
      }
      log.error(
          "Matrix Error: Could not leave room ({}). Status: {}, Response: {}",
          roomId,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      return false;
    } catch (Exception ex) {
      log.error("Matrix Error: Could not leave room ({}). Reason: {}", roomId, ex.getMessage());
      return false;
    }
  }

  /**
   * Bans a user from a Matrix room ({@code POST /rooms/{roomId}/ban}). A ban both removes the user
   * from the room and prevents them from re-joining until unbanned, which is the Matrix-native
   * equivalent of the former Rocket.Chat "mute/ban from chat".
   *
   * <p>Best-effort: never throws. A ban of a user who is already banned is treated as success.
   *
   * @param roomId the Matrix room ID
   * @param userId the full Matrix user ID to ban
   * @param accessToken access token of a user with permission to ban (room moderator/admin)
   * @return true when the user is banned afterwards, false when the ban failed
   */
  public boolean banUserFromRoom(String roomId, String userId, String accessToken) {
    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      Map<String, Object> body = new HashMap<>();
      body.put("user_id", userId);

      HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

      var url = buildUrl(ENDPOINT_BAN_ROOM, Map.of("roomId", roomId));
      log.info("Banning Matrix user {} from room {}", userId, roomId);

      var response = restTemplate.postForEntity(url, request, Map.class);
      if (response.getStatusCode().is2xxSuccessful()) {
        log.info("Successfully banned Matrix user {} from room {}", userId, roomId);
        return true;
      }
      log.warn(
          "Failed to ban Matrix user {} from room {}. Status: {}",
          userId,
          roomId,
          response.getStatusCode());
      return false;
    } catch (HttpClientErrorException ex) {
      log.error(
          "Matrix Error: Could not ban user ({}) from room ({}). Status: {}, Response: {}",
          userId,
          roomId,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      return false;
    } catch (Exception ex) {
      log.error(
          "Matrix Error: Could not ban user ({}) from room ({}). Reason: {}",
          userId,
          roomId,
          ex.getMessage());
      return false;
    }
  }

  /**
   * Lifts a ban previously placed with {@link #banUserFromRoom} ({@code POST
   * /rooms/{roomId}/unban}). Best-effort: never throws.
   *
   * @param roomId the Matrix room ID
   * @param userId the full Matrix user ID to unban
   * @param accessToken access token of a user with permission to unban
   * @return true when the unban succeeded, false otherwise
   */
  public boolean unbanUserFromRoom(String roomId, String userId, String accessToken) {
    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      Map<String, Object> body = new HashMap<>();
      body.put("user_id", userId);

      HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

      var url = buildUrl(ENDPOINT_UNBAN_ROOM, Map.of("roomId", roomId));
      log.info("Unbanning Matrix user {} from room {}", userId, roomId);

      var response = restTemplate.postForEntity(url, request, Map.class);
      if (response.getStatusCode().is2xxSuccessful()) {
        log.info("Successfully unbanned Matrix user {} from room {}", userId, roomId);
        return true;
      }
      log.warn(
          "Failed to unban Matrix user {} from room {}. Status: {}",
          userId,
          roomId,
          response.getStatusCode());
      return false;
    } catch (HttpClientErrorException ex) {
      if (ex.getStatusCode().value() == 403 || ex.getStatusCode().value() == 404) {
        log.info(
            "Matrix user {} was not banned in room {} (status {}); nothing to unban",
            userId,
            roomId,
            ex.getStatusCode());
        return true;
      }
      log.error(
          "Matrix Error: Could not unban user ({}) from room ({}). Status: {}, Response: {}",
          userId,
          roomId,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      return false;
    } catch (Exception ex) {
      log.error(
          "Matrix Error: Could not unban user ({}) from room ({}). Reason: {}",
          userId,
          roomId,
          ex.getMessage());
      return false;
    }
  }

  public boolean setUserPowerLevel(
      String roomId, String userId, int powerLevel, String accessToken) {
    try {
      var url = buildUrl(ENDPOINT_POWER_LEVELS, Map.of("roomId", roomId));

      HttpHeaders headers = getClientHttpHeaders(accessToken);
      HttpEntity<Void> getRequest = new HttpEntity<>(headers);

      ResponseEntity<Map> currentResponse =
          restTemplate.exchange(url, HttpMethod.GET, getRequest, Map.class);

      if (currentResponse.getBody() == null) {
        log.error("Failed to get current power levels for room {}", roomId);
        return false;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> powerLevels = new HashMap<>(currentResponse.getBody());

      Map<String, Object> users = extractUsers(powerLevels);

      Map<String, Object> updatedUsers = new HashMap<>(users);
      updatedUsers.put(userId, powerLevel);
      powerLevels.put("users", updatedUsers);

      HttpEntity<Map<String, Object>> updateRequest = new HttpEntity<>(powerLevels, headers);
      restTemplate.put(url, updateRequest);

      log.info("Set power level {} for user {} in room {}", powerLevel, userId, roomId);
      return true;
    } catch (HttpClientErrorException ex) {
      log.error(
          "Matrix Error: Could not set power level for user ({}) in room ({}). Status: {}, Response: {}",
          userId,
          roomId,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      return false;
    } catch (Exception e) {
      log.error(
          "Failed to set power level for user {} in room {}: {}", userId, roomId, e.getMessage());
      return false;
    }
  }

  /**
   * Sets the room-wide {@code events_default} power level. With member power level 0 and {@code
   * events_default} raised above it, no ordinary member can post any more — the protocol-level
   * read-only switch used when a Team-Besprechung is archived (US#473 / ADR-016).
   */
  public boolean setRoomEventsDefaultPowerLevel(String roomId, int powerLevel, String accessToken) {
    try {
      var url = buildUrl(ENDPOINT_POWER_LEVELS, Map.of("roomId", roomId));

      HttpHeaders headers = getClientHttpHeaders(accessToken);
      HttpEntity<Void> getRequest = new HttpEntity<>(headers);

      ResponseEntity<Map> currentResponse =
          restTemplate.exchange(url, HttpMethod.GET, getRequest, Map.class);

      if (currentResponse.getBody() == null) {
        log.error("Failed to get current power levels for room {}", roomId);
        return false;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> powerLevels = new HashMap<>(currentResponse.getBody());
      powerLevels.put("events_default", powerLevel);

      HttpEntity<Map<String, Object>> updateRequest = new HttpEntity<>(powerLevels, headers);
      restTemplate.put(url, updateRequest);

      log.info("Set events_default power level {} in room {}", powerLevel, roomId);
      return true;
    } catch (HttpClientErrorException ex) {
      log.error(
          "Matrix Error: Could not set events_default in room ({}). Status: {}, Response: {}",
          roomId,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      return false;
    } catch (Exception e) {
      log.error("Failed to set events_default in room {}: {}", roomId, e.getMessage());
      return false;
    }
  }

  public boolean removeUserFromRoom(String roomId, String userId, String accessToken) {
    try {
      var url = buildUrl(ENDPOINT_MEMBERSHIP, Map.of("roomId", roomId, "userId", userId));

      Map<String, Object> membershipEvent = new HashMap<>();
      membershipEvent.put("membership", "leave");

      HttpHeaders headers = getClientHttpHeaders(accessToken);
      HttpEntity<Map<String, Object>> request = new HttpEntity<>(membershipEvent, headers);

      restTemplate.put(url, request);

      log.info("Removed user {} from room {}", userId, roomId);
      return true;
    } catch (HttpClientErrorException ex) {
      log.error(
          "Matrix Error: Could not remove user ({}) from room ({}). Status: {}, Response: {}",
          userId,
          roomId,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      return false;
    } catch (Exception e) {
      log.error("Failed to remove user {} from room {}: {}", userId, roomId, e.getMessage());
      return false;
    }
  }

  private HttpHeaders getClientHttpHeaders(String accessToken) {
    var headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + accessToken);
    return headers;
  }

  private Map<String, Object> extractUsers(Map<String, Object> powerLevels) {
    Object users = powerLevels.get("users");
    if (users instanceof Map) {
      Map<?, ?> usersMap = (Map<?, ?>) users;
      Map<String, Object> result = new HashMap<>();
      usersMap.forEach((key, value) -> result.put(String.valueOf(key), value));
      return result;
    }
    return new HashMap<>();
  }

  private URI buildUrl(String endpoint) {
    return MatrixUrlBuilder.buildUrl(matrixConfig, endpoint);
  }

  private URI buildUrl(String endpoint, Map<String, ?> uriVariables) {
    return MatrixUrlBuilder.buildUrl(matrixConfig, endpoint, uriVariables);
  }
}
