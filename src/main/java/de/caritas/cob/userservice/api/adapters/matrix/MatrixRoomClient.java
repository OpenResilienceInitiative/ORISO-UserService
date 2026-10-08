package de.caritas.cob.userservice.api.adapters.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserResponseDTO;
import de.caritas.cob.userservice.api.config.observability.LiveChatDiagnosticMetrics;
import de.caritas.cob.userservice.api.config.observability.LiveChatDiagnosticMetrics.Outcome;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
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
  private static final int INVITE_USER_MAX_ATTEMPTS = 3;
  private static final long DEFAULT_INVITE_RETRY_DELAY_MS = 1000L;
  private static final Pattern RETRY_AFTER_MS_PATTERN =
      Pattern.compile("\"retry_after_ms\"\\s*:\\s*(\\d+)");

  private final MatrixConfig matrixConfig;
  private final RestTemplate restTemplate;
  private final LiveChatDiagnosticMetrics diagnosticMetrics;

  private static final String ENDPOINT_ROOM_STATE = "/_matrix/client/v3/rooms/{roomId}/state";
  private static final long MAX_SAFE_POWER = 9007199254740991L;
  private static final java.util.Set<String> CLOSED_CONTENT_EVENTS =
      java.util.Set.of(
          "m.room.message",
          "m.room.encrypted",
          "m.reaction",
          "m.room.redaction",
          "m.call.member",
          "org.matrix.msc3401.call.member",
          "org.oriso.call.invite",
          "m.call.invite",
          "m.call.answer",
          "m.call.candidates",
          "m.call.hangup",
          "m.call.reject",
          "m.call.select_answer",
          "m.call.negotiate",
          "m.call.replaces",
          "m.call.sdp_stream_metadata_changed",
          "m.call.notify");

  /** Verify a bounded technical-operator closure without changing any person's privileges. */
  public boolean closeRoomForMessagesVerified(
      String roomId, String operatorUserId, String accessToken) {
    if (roomId == null
        || roomId.isBlank()
        || operatorUserId == null
        || operatorUserId.isBlank()
        || accessToken == null
        || accessToken.isBlank()) return false;
    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);
      var identity =
          restTemplate
              .exchange(
                  buildUrl("/_matrix/client/v3/account/whoami"),
                  HttpMethod.GET,
                  new HttpEntity<>(headers),
                  Map.class)
              .getBody();
      if (identity == null || !operatorUserId.equals(identity.get("user_id"))) return false;
      var before = closureState(roomId, operatorUserId, headers);
      long threshold = Math.addExact(before.highestOtherPower(), 1L);
      if (threshold > MAX_SAFE_POWER) return false;
      var updated = new HashMap<String, Object>(before.power());
      var events = new HashMap<String, Object>(objectMap(updated.get("events")));
      var protectedEvents = new java.util.HashSet<>(CLOSED_CONTENT_EVENTS);
      events.keySet().stream()
          .filter(MatrixRoomClient::isCallContent)
          .forEach(protectedEvents::add);
      long defaultRequired = power(updated, "events_default", 0, before.version());
      threshold = Math.max(threshold, defaultRequired);
      for (String type : protectedEvents) {
        threshold = Math.max(threshold, power(events, type, 0, before.version()));
      }
      if (!before.infiniteOperator() && threshold > before.operatorPower()) return false;
      long powerEventRequired =
          power(
              events,
              "m.room.power_levels",
              power(updated, "state_default", 50, before.version()),
              before.version());
      if (!before.infiniteOperator() && powerEventRequired > before.operatorPower()) return false;
      updated.put("events_default", threshold);
      for (String type : protectedEvents) events.put(type, threshold);
      updated.put("events", events);
      restTemplate.exchange(
          buildUrl(ENDPOINT_POWER_LEVELS, Map.of("roomId", roomId)),
          HttpMethod.PUT,
          new HttpEntity<>(updated, headers),
          Map.class);
      var after = closureState(roomId, operatorUserId, headers);
      // Independent state readback must preserve identity/power facts and certify every write kind.
      if (before.version() != after.version()
          || !before.creators().equals(after.creators())
          || !objectMap(before.power().get("users")).equals(objectMap(after.power().get("users")))
          || !java.util.Objects.equals(
              before.power().get("users_default"), after.power().get("users_default")))
        return false;
      long blockedAbove = Math.max(before.highestOtherPower(), after.highestOtherPower());
      if (power(after.power(), "events_default", 0, after.version()) <= blockedAbove) return false;
      var actualEvents = objectMap(after.power().get("events"));
      protectedEvents.addAll(
          actualEvents.keySet().stream().filter(MatrixRoomClient::isCallContent).toList());
      for (String type : protectedEvents) {
        long fallback =
            type.equals("m.call.member") || type.equals("org.matrix.msc3401.call.member")
                ? power(after.power(), "state_default", 50, after.version())
                : power(after.power(), "events_default", 0, after.version());
        if (power(actualEvents, type, fallback, after.version()) <= blockedAbove) return false;
      }
      return true;
    } catch (RuntimeException ex) {
      // Provider bodies and credentials are deliberately excluded from diagnostic logging.
      log.warn("Matrix enquiry closure could not be verified");
      return false;
    }
  }

  private record ClosureState(
      Map<String, Object> power,
      int version,
      java.util.Set<String> creators,
      long operatorPower,
      boolean infiniteOperator,
      long highestOtherPower) {}

  private ClosureState closureState(String roomId, String operator, HttpHeaders headers) {
    var state =
        restTemplate
            .exchange(
                buildUrl(ENDPOINT_ROOM_STATE, Map.of("roomId", roomId)),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                java.util.List.class)
            .getBody();
    if (state == null) throw new IllegalStateException("Missing state");
    Map<String, Object> creation = null;
    Map<String, Object> levels = null;
    String creator = null;
    var members = new java.util.HashSet<String>();
    for (Object raw : state) {
      var event = objectMap(raw);
      String type = String.valueOf(event.get("type"));
      String key = String.valueOf(event.get("state_key"));
      var content = objectMap(event.get("content"));
      if ("m.room.create".equals(type) && key.isEmpty()) {
        if (creation != null) throw new IllegalStateException("Duplicate create state");
        creation = content;
        creator = (String) event.get("sender");
      } else if ("m.room.power_levels".equals(type) && key.isEmpty()) {
        if (levels != null) throw new IllegalStateException("Duplicate power state");
        levels = content;
      } else if ("m.room.member".equals(type) && "join".equals(content.get("membership"))) {
        members.add(key);
      }
    }
    if (creation == null || creator == null || creator.isBlank() || !members.contains(operator))
      throw new IllegalStateException("Incomplete creator/operator state");
    String versionValue = String.valueOf(creation.getOrDefault("room_version", "1"));
    if (!versionValue.matches("[1-9]|1[0-2]"))
      throw new IllegalStateException("Unsupported room version");
    int version = Integer.parseInt(versionValue);
    if (version < 1 || version > 12) throw new IllegalStateException("Unsupported room version");
    var creators = new java.util.HashSet<String>();
    creators.add(creator);
    if (version == 12 && creation.containsKey("additional_creators")) {
      if (!(creation.get("additional_creators") instanceof java.util.List<?> extra))
        throw new IllegalStateException("Invalid creators");
      for (Object id : extra) {
        if (!(id instanceof String value) || value.isBlank())
          throw new IllegalStateException("Invalid creator");
        creators.add(value);
      }
    }
    if (version == 12 && creators.stream().anyMatch(id -> !operator.equals(id)))
      throw new IllegalStateException("Another infinite-power creator");
    if (levels == null) {
      // Pre-v12 absence grants the room creator 100; other members remain at zero.
      levels = new HashMap<>();
      if (version < 12) levels.put("users", Map.of(creator, 100));
    }
    var users = objectMap(levels.get("users"));
    if (version == 12 && creators.stream().anyMatch(users::containsKey))
      throw new IllegalStateException("Creator in power map");
    long defaultPower = power(levels, "users_default", 0, version);
    long operatorPower = power(users, operator, defaultPower, version);
    long highest = defaultPower;
    var possibleWriters = new java.util.HashSet<>(members);
    possibleWriters.addAll(users.keySet());
    possibleWriters.remove(operator);
    for (String member : possibleWriters)
      highest = Math.max(highest, power(users, member, defaultPower, version));
    return new ClosureState(
        levels,
        version,
        java.util.Set.copyOf(creators),
        operatorPower,
        version == 12 && creators.contains(operator),
        highest);
  }

  private static boolean isCallContent(String type) {
    return type.startsWith("m.call.")
        || type.startsWith("org.matrix.msc3401.call.")
        || type.startsWith("org.oriso.call.");
  }

  private static Map<String, Object> objectMap(Object value) {
    if (value == null) return Map.of();
    if (!(value instanceof Map<?, ?> raw)) throw new IllegalStateException("Invalid state object");
    var result = new HashMap<String, Object>();
    raw.forEach(
        (key, entry) -> {
          if (!(key instanceof String text)) throw new IllegalStateException("Invalid state key");
          result.put(text, entry);
        });
    return result;
  }

  private static long power(Map<String, Object> map, String key, long fallback, int version) {
    Object raw = map.get(key);
    if (raw == null) return fallback;
    java.math.BigDecimal numeric;
    if (raw instanceof Number number) numeric = new java.math.BigDecimal(number.toString());
    else if (version <= 9 && raw instanceof String text) numeric = new java.math.BigDecimal(text);
    else throw new IllegalStateException("Invalid power level");
    long value = numeric.longValueExact();
    if (value < -MAX_SAFE_POWER || value > MAX_SAFE_POWER)
      throw new IllegalStateException("Invalid power range");
    return value;
  }

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
      if (response.getBody() == null
          || response.getBody().getRoomId() == null
          || response.getBody().getRoomId().isBlank()) {
        throw new IllegalStateException("Matrix create-room response did not contain a room ID");
      }

      diagnosticMetrics.recordRoomCreation(encryptionEnabled, Outcome.SUCCESS);
      log.info(
          "Successfully created Matrix room: {} with ID: {}",
          roomName,
          response.getBody().getRoomId());

      return response;
    } catch (HttpClientErrorException ex) {
      diagnosticMetrics.recordRoomCreation(encryptionEnabled, Outcome.FAILURE);
      log.error(
          "Matrix Error: Could not create room ({}) in Matrix. Status: {}, Response: {}",
          roomName,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      throw new MatrixCreateRoomException(
          String.format(
              "Could not create room (%s) in Matrix: %s", roomName, ex.getResponseBodyAsString()));
    } catch (Exception ex) {
      diagnosticMetrics.recordRoomCreation(encryptionEnabled, Outcome.FAILURE);
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

    var headers = getClientHttpHeaders(accessToken);
    headers.setContentType(MediaType.APPLICATION_JSON);

    var inviteRequest = new MatrixInviteUserRequestDTO();
    inviteRequest.setUserId(userId);

    HttpEntity<MatrixInviteUserRequestDTO> request = new HttpEntity<>(inviteRequest, headers);

    var url = buildUrl(ENDPOINT_INVITE_USER, Map.of("roomId", roomId));

    for (int attempt = 1; attempt <= INVITE_USER_MAX_ATTEMPTS; attempt++) {
      try {
        log.info("Inviting Matrix user: {} to room: {} at URL: {}", userId, roomId, url);

        var response = restTemplate.postForEntity(url, request, MatrixInviteUserResponseDTO.class);

        log.info("Successfully invited Matrix user: {} to room: {}", userId, roomId);

        return response;
      } catch (HttpClientErrorException ex) {
        if (isRateLimited(ex) && attempt < INVITE_USER_MAX_ATTEMPTS) {
          waitBeforeInviteRetry(userId, roomId, attempt, retryAfterMs(ex));
          continue;
        }

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

    throw new MatrixInviteUserException(
        String.format("Could not invite user (%s) to room (%s) in Matrix", userId, roomId));
  }

  private boolean isRateLimited(HttpClientErrorException ex) {
    return ex.getStatusCode().value() == 429
        || ex.getResponseBodyAsString().contains("M_LIMIT_EXCEEDED");
  }

  private long retryAfterMs(HttpClientErrorException ex) {
    var matcher = RETRY_AFTER_MS_PATTERN.matcher(ex.getResponseBodyAsString());
    if (matcher.find()) {
      try {
        return Long.parseLong(matcher.group(1));
      } catch (NumberFormatException ignored) {
        return DEFAULT_INVITE_RETRY_DELAY_MS;
      }
    }
    return DEFAULT_INVITE_RETRY_DELAY_MS;
  }

  private void waitBeforeInviteRetry(String userId, String roomId, int attempt, long retryAfterMs)
      throws MatrixInviteUserException {
    log.warn(
        "Matrix invite for user {} to room {} was rate limited; retrying attempt {} of {} after {} ms",
        userId,
        roomId,
        attempt + 1,
        INVITE_USER_MAX_ATTEMPTS,
        retryAfterMs);
    try {
      Thread.sleep(retryAfterMs);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new MatrixInviteUserException(
          String.format("Interrupted while retrying Matrix invite for user (%s)", userId), ex);
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
   * from the room and prevents them from re-joining until unbanned.
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
