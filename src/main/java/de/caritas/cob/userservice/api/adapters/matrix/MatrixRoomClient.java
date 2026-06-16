package de.caritas.cob.userservice.api.adapters.matrix;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserResponseDTO;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
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
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatrixRoomClient {

  private static final String ENDPOINT_CREATE_ROOM = "/_matrix/client/r0/createRoom";
  private static final String ENDPOINT_INVITE_USER = "/_matrix/client/r0/rooms/{roomId}/invite";
  private static final String ENDPOINT_JOIN_ROOM = "/_matrix/client/r0/rooms/{roomId}/join";
  private static final String ENDPOINT_POWER_LEVELS =
      "/_matrix/client/r0/rooms/{roomId}/state/m.room.power_levels";
  private static final String ENDPOINT_MEMBERSHIP =
      "/_matrix/client/r0/rooms/{roomId}/state/m.room.member/{userId}";

  private final MatrixConfig matrixConfig;
  private final RestTemplate restTemplate;

  public ResponseEntity<MatrixCreateRoomResponseDTO> createRoom(
      String roomName, String roomAlias, String accessToken) throws MatrixCreateRoomException {

    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      var roomCreateRequest = new MatrixCreateRoomRequestDTO();
      roomCreateRequest.setName(roomName);
      roomCreateRequest.setRoomAliasName(roomAlias);
      roomCreateRequest.setPreset("private_chat");
      roomCreateRequest.setVisibility("private");

      // Matrix E2EE is disabled because the frontend applies its own encryption layer.
      roomCreateRequest.setInitialState(new java.util.ArrayList<>());

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

  public boolean setUserPowerLevel(
      String roomId, String userId, int powerLevel, String accessToken) {
    try {
      String url = buildUrl(ENDPOINT_POWER_LEVELS, Map.of("roomId", roomId));

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

  public boolean removeUserFromRoom(String roomId, String userId, String accessToken) {
    try {
      String url = buildUrl(ENDPOINT_MEMBERSHIP, Map.of("roomId", roomId, "userId", userId));

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

  private String buildUrl(String endpoint) {
    return UriComponentsBuilder.fromUriString(matrixConfig.getApiUrl(endpoint))
        .build()
        .encode()
        .toUriString();
  }

  private String buildUrl(String endpoint, Map<String, ?> uriVariables) {
    return UriComponentsBuilder.fromUriString(matrixConfig.getApiUrl(endpoint))
        .buildAndExpand(uriVariables)
        .encode()
        .toUriString();
  }
}
