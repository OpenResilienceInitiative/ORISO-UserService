package de.caritas.cob.userservice.api.adapters.matrix;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserResponseDTO;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

/** Service for Matrix Synapse functionalities. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatrixSynapseService {

  private static final String ENDPOINT_REGISTER_USER = "/_synapse/admin/v1/register";
  private static final String ENDPOINT_LOGIN = "/_matrix/client/r0/login";
  private static final String ENDPOINT_CREATE_ROOM = "/_matrix/client/r0/createRoom";
  private static final String ENDPOINT_INVITE_USER = "/_matrix/client/r0/rooms/{roomId}/invite";
  private static final String ENDPOINT_JOIN_ROOM = "/_matrix/client/r0/rooms/{roomId}/join";
  private static final String ENDPOINT_SYNC = "/_matrix/client/r0/sync";
  private static final String ENDPOINT_UPDATE_USER_ADMIN = "/_synapse/admin/v2/users/{userId}";
  private static final String ENDPOINT_MEDIA_UPLOAD = "/_matrix/media/r0/upload";
  private static final String ENDPOINT_JOINED_ROOMS = "/_matrix/client/r0/joined_rooms";

  private final MatrixConfig matrixConfig;
  private final RestTemplate restTemplate;

  // Cache for Matrix access tokens (username -> access token)
  private final java.util.Map<String, String> accessTokenCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  // Cache for Matrix sync tokens (username -> next_batch token)
  private final java.util.Map<String, String> syncTokenCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  // Cached admin access token for admin operations
  private String cachedAdminToken = null;
  private long adminTokenExpiry = 0;

  /**
   * Creates a new user in Matrix Synapse.
   *
   * @param username the username
   * @param password the password
   * @param displayName the display name
   * @return the user creation response
   * @throws MatrixCreateUserException on failure
   */
  public ResponseEntity<MatrixCreateUserResponseDTO> createUser(
      String username, String password, String displayName) throws MatrixCreateUserException {

    try {
      // First, get a nonce from Matrix
      var nonceUrl = matrixConfig.getApiUrl(ENDPOINT_REGISTER_USER);
      var nonceResponse = restTemplate.getForEntity(nonceUrl, String.class);

      // Parse nonce from response
      String nonce = extractNonceFromResponse(nonceResponse.getBody());
      if (nonce == null) {
        throw new MatrixCreateUserException("Could not obtain nonce from Matrix");
      }

      var headers = getAdminHttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      // Generate MAC for authentication
      String mac = generateMac(nonce, username, password, false);

      var userCreateRequest = new MatrixCreateUserRequestDTO();
      userCreateRequest.setUsername(username);
      userCreateRequest.setPassword(password);
      userCreateRequest.setDisplayName(displayName);
      userCreateRequest.setAdmin(false);
      userCreateRequest.setNonce(nonce);
      userCreateRequest.setMac(mac);

      HttpEntity<MatrixCreateUserRequestDTO> request = new HttpEntity<>(userCreateRequest, headers);

      log.info("Creating Matrix user: {} at URL: {}", username, nonceUrl);

      var response =
          restTemplate.postForEntity(nonceUrl, request, MatrixCreateUserResponseDTO.class);

      if (nonNull(response.getBody()) && nonNull(response.getBody().getUserId())) {
        log.info(
            "Successfully created Matrix user: {} with ID: {}",
            username,
            response.getBody().getUserId());
      }

      return response;
    } catch (HttpClientErrorException ex) {
      log.error(
          "Matrix Error: Could not create user ({}) in Matrix. Status: {}, Response: {}",
          username,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      throw new MatrixCreateUserException(
          String.format(
              "Could not create user (%s) in Matrix: %s", username, ex.getResponseBodyAsString()));
    } catch (Exception ex) {
      log.error("Matrix Error: Could not create user ({}) in Matrix. Reason", username, ex);
      throw new MatrixCreateUserException(
          String.format("Could not create user (%s) in Matrix", username));
    }
  }

  /**
   * Creates a new ADMIN user in Matrix Synapse.
   *
   * @param username the username
   * @param password the password
   * @return the response with user ID
   */
  private ResponseEntity<MatrixCreateUserResponseDTO> createAdminUser(
      String username, String password) throws MatrixCreateUserException {

    try {
      // First, get a nonce from Matrix
      var nonceUrl = matrixConfig.getApiUrl(ENDPOINT_REGISTER_USER);
      var nonceResponse = restTemplate.getForEntity(nonceUrl, String.class);

      String nonce = extractNonceFromResponse(nonceResponse.getBody());
      if (nonce == null) {
        throw new MatrixCreateUserException("Could not obtain nonce from Matrix");
      }

      var headers = getAdminHttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      // Generate MAC for authentication with admin=true
      String mac = generateMac(nonce, username, password, true);

      var userCreateRequest = new MatrixCreateUserRequestDTO();
      userCreateRequest.setUsername(username);
      userCreateRequest.setPassword(password);
      userCreateRequest.setDisplayName("Caritas System Admin");
      userCreateRequest.setAdmin(true); // THIS IS THE KEY DIFFERENCE
      userCreateRequest.setNonce(nonce);
      userCreateRequest.setMac(mac);

      HttpEntity<MatrixCreateUserRequestDTO> request = new HttpEntity<>(userCreateRequest, headers);

      log.info("Creating Matrix ADMIN user: {} at URL: {}", username, nonceUrl);

      var response =
          restTemplate.postForEntity(nonceUrl, request, MatrixCreateUserResponseDTO.class);

      if (nonNull(response.getBody()) && nonNull(response.getBody().getUserId())) {
        log.info(
            "Successfully created Matrix ADMIN user: {} with ID: {}",
            username,
            response.getBody().getUserId());
      }

      return response;
    } catch (Exception ex) {
      log.error("Matrix Error: Could not create ADMIN user ({}): {}", username, ex.getMessage());
      throw new MatrixCreateUserException(
          String.format("Could not create ADMIN user (%s) in Matrix", username));
    }
  }

  /**
   * Public method to get admin token for operations requiring Matrix authentication.
   *
   * @return admin access token, or null if failed
   */
  public String getAdminToken() {
    return getAdminAccessToken();
  }

  /**
   * Gets or creates an admin access token for administrative operations. Creates a technical admin
   * user if it doesn't exist.
   *
   * @return admin access token, or null if failed
   */
  private String getAdminAccessToken() {
    // Check if cached token is still valid (expires in 1 hour, we refresh after 50 minutes)
    long now = System.currentTimeMillis();
    if (cachedAdminToken != null && now < adminTokenExpiry) {
      return cachedAdminToken;
    }

    try {
      String adminUsername = "caritas_admin";
      String adminPassword = "@CaritasAdmin2025!";

      // Try to login first
      String token = loginUser(adminUsername, adminPassword);

      // If login fails, create the ADMIN user (with admin=true)
      if (token == null) {
        log.info("Creating Matrix admin user: {}", adminUsername);
        try {
          // Create admin user manually with admin=true
          createAdminUser(adminUsername, adminPassword);
          token = loginUser(adminUsername, adminPassword);
        } catch (Exception e) {
          log.warn("Could not create admin user, trying login again: {}", e.getMessage());
          token = loginUser(adminUsername, adminPassword);
        }
      }

      if (token != null) {
        cachedAdminToken = token;
        adminTokenExpiry = now + (50 * 60 * 1000); // 50 minutes
        log.debug("Cached admin token (expires in 50 min)");
      }

      return token;
    } catch (Exception e) {
      log.error("Failed to get admin access token: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Updates a Matrix user's display name using Synapse ADMIN v2 API.
   *
   * @param matrixUserId the full Matrix user ID (e.g., @username:domain)
   * @param displayName the new display name
   * @return true if successful, false otherwise
   */
  public boolean updateUserDisplayName(String matrixUserId, String displayName) {
    try {
      // Get admin token
      String adminToken = getAdminAccessToken();
      if (adminToken == null) {
        log.warn("Could not get admin token for Matrix display name update");
        return false;
      }

      // Update display name using Synapse ADMIN v2 API
      String url =
          matrixConfig.getApiUrl(ENDPOINT_UPDATE_USER_ADMIN.replace("{userId}", matrixUserId));

      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(adminToken);

      var body = new java.util.HashMap<String, Object>();
      body.put("displayname", displayName);

      var request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.exchange(
              url, org.springframework.http.HttpMethod.PUT, request, String.class);

      log.info(
          "Successfully updated Matrix display name for user: {} to: {}",
          matrixUserId,
          displayName);
      return response.getStatusCode().is2xxSuccessful();

    } catch (Exception ex) {
      log.warn(
          "Failed to update Matrix display name for user {}: {}", matrixUserId, ex.getMessage());
      return false;
    }
  }

  /**
   * Logs in a Matrix user and returns access token.
   *
   * @param username the username
   * @param password the password
   * @return the access token
   */
  public String loginUser(String username, String password) {
    // Check cache first
    if (accessTokenCache.containsKey(username)) {
      return accessTokenCache.get(username);
    }

    try {
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      var loginRequest = new java.util.HashMap<String, Object>();
      loginRequest.put("type", "m.login.password");
      loginRequest.put("user", username);
      loginRequest.put("password", password);

      HttpEntity<java.util.Map<String, Object>> request = new HttpEntity<>(loginRequest, headers);

      var url = matrixConfig.getApiUrl(ENDPOINT_LOGIN);
      log.info("Logging in Matrix user: {} at URL: {}", username, url);

      var response = restTemplate.postForEntity(url, request, java.util.Map.class);

      if (response.getBody() != null && response.getBody().containsKey("access_token")) {
        String accessToken = (String) response.getBody().get("access_token");
        accessTokenCache.put(username, accessToken);
        log.info("Successfully logged in Matrix user: {}", username);
        return accessToken;
      }

      log.error("Matrix login failed for user: {} - no access token in response", username);
      return null;
    } catch (Exception ex) {
      log.error(
          "Matrix Error: Could not login user ({}) in Matrix. Reason: {}",
          username,
          ex.getMessage());
      return null;
    }
  }

  /**
   * Creates a new room in Matrix Synapse using the consultant's credentials.
   *
   * @param roomName the room name
   * @param roomAlias the room alias
   * @param consultantUsername the consultant's username
   * @param consultantPassword the consultant's password (from session context)
   * @return the room creation response
   * @throws MatrixCreateRoomException on failure
   */
  public ResponseEntity<MatrixCreateRoomResponseDTO> createRoomAsConsultant(
      String roomName, String roomAlias, String consultantUsername, String consultantPassword)
      throws MatrixCreateRoomException {

    String accessToken = loginUser(consultantUsername, consultantPassword);
    if (accessToken == null) {
      throw new MatrixCreateRoomException("Could not obtain access token for consultant");
    }

    return createRoom(roomName, roomAlias, accessToken);
  }

  /**
   * Creates a new room in Matrix Synapse.
   *
   * @param roomName the room name
   * @param roomAlias the room alias
   * @param accessToken the access token
   * @return the room creation response
   * @throws MatrixCreateRoomException on failure
   */
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

      // NOTE: Matrix E2EE is DISABLED because frontend has its own custom encryption layer
      // The frontend uses RSA-OAEP + AES-GCM encryption on top of Matrix
      // Enabling Matrix E2EE causes double-encryption and breaks the frontend's crypto

      // Fix: Set initial_state to empty list to prevent NoneType iteration error in Matrix
      // Synapse
      roomCreateRequest.setInitialState(new java.util.ArrayList<>());

      HttpEntity<MatrixCreateRoomRequestDTO> request = new HttpEntity<>(roomCreateRequest, headers);

      var url = matrixConfig.getApiUrl(ENDPOINT_CREATE_ROOM);
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

  /**
   * Invites a user to a Matrix room.
   *
   * @param roomId the room ID
   * @param userId the user ID to invite
   * @param accessToken the access token
   * @return the invite response
   * @throws MatrixInviteUserException on failure
   */
  public ResponseEntity<MatrixInviteUserResponseDTO> inviteUserToRoom(
      String roomId, String userId, String accessToken) throws MatrixInviteUserException {

    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      var inviteRequest = new MatrixInviteUserRequestDTO();
      inviteRequest.setUserId(userId);

      HttpEntity<MatrixInviteUserRequestDTO> request = new HttpEntity<>(inviteRequest, headers);

      var url = matrixConfig.getApiUrl(ENDPOINT_INVITE_USER.replace("{roomId}", roomId));
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

  /**
   * Accepts a room invitation (joins the room) on behalf of a user.
   *
   * @param roomId the room ID to join
   * @param accessToken the access token of the user who was invited
   * @return true if successful, false otherwise
   */
  public boolean joinRoom(String roomId, String accessToken) {
    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      // Empty body for join request
      HttpEntity<String> request = new HttpEntity<>("{}", headers);

      var url = matrixConfig.getApiUrl(ENDPOINT_JOIN_ROOM.replace("{roomId}", roomId));
      log.info("Accepting room invitation (joining room): {} at URL: {}", roomId, url);

      var response = restTemplate.postForEntity(url, request, java.util.Map.class);

      if (response.getStatusCode().is2xxSuccessful()) {
        log.info("Successfully joined Matrix room: {}", roomId);
        return true;
      } else {
        log.warn("Failed to join Matrix room: {}. Status: {}", roomId, response.getStatusCode());
        return false;
      }
    } catch (HttpClientErrorException ex) {
      // Check if user is already in the room (this is not an error)
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
   * Sends a message to a Matrix room.
   *
   * @param roomId the room ID
   * @param message the message text
   * @param accessToken the access token
   * @return the send response with event_id
   */
  public java.util.Map<String, Object> sendMessage(
      String roomId, String message, String accessToken) {
    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      var messageBody = new java.util.HashMap<String, Object>();
      messageBody.put("msgtype", "m.text");
      messageBody.put("body", message);

      HttpEntity<java.util.Map<String, Object>> request = new HttpEntity<>(messageBody, headers);

      String txnId = java.util.UUID.randomUUID().toString();
      var url =
          matrixConfig.getApiUrl(
              "/_matrix/client/r0/rooms/" + roomId + "/send/m.room.message/" + txnId);

      log.info("Sending message to Matrix room: {}", roomId);

      var response =
          restTemplate.exchange(
              url, org.springframework.http.HttpMethod.PUT, request, java.util.Map.class);

      return response.getBody();
    } catch (Exception ex) {
      log.error(
          "Matrix Error: Could not send message to room ({}). Reason: {}", roomId, ex.getMessage());
      return java.util.Map.of("error", ex.getMessage());
    }
  }

  /**
   * Gets messages from a Matrix room.
   *
   * @param roomId the room ID
   * @param accessToken the access token
   * @return the list of messages
   */
  public java.util.List<java.util.Map<String, Object>> getRoomMessages(
      String roomId, String accessToken) {
    try {
      var headers = getClientHttpHeaders(accessToken);

      HttpEntity<Void> request = new HttpEntity<>(headers);

      var url =
          matrixConfig.getApiUrl(
              "/_matrix/client/r0/rooms/" + roomId + "/messages?dir=b&limit=100");

      log.info("Getting messages from Matrix room: {}", roomId);

      var response =
          restTemplate.exchange(
              url, org.springframework.http.HttpMethod.GET, request, java.util.Map.class);

      if (response.getBody() != null && response.getBody().containsKey("chunk")) {
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> chunk =
            (java.util.List<java.util.Map<String, Object>>) response.getBody().get("chunk");

        // Return ALL message types (text, image, file, video, audio)
        return chunk.stream()
            .filter(event -> "m.room.message".equals(event.get("type")))
            .collect(java.util.stream.Collectors.toList());
      }

      return new java.util.ArrayList<>();
    } catch (Exception ex) {
      log.error(
          "Matrix Error: Could not get messages from room ({}). Reason: {}",
          roomId,
          ex.getMessage());
      return new java.util.ArrayList<>();
    }
  }

  /**
   * Performs a sync with Matrix to get new messages for a specific room.
   *
   * @param roomId the room ID to filter for
   * @param accessToken the access token
   * @param username the username (for caching sync token)
   * @param timeout timeout in milliseconds for long-polling (0 for immediate return)
   * @return map containing messages and next_batch token
   */
  public java.util.Map<String, Object> syncRoom(
      String roomId, String accessToken, String username, int timeout) {
    try {
      var headers = getClientHttpHeaders(accessToken);

      HttpEntity<Void> request = new HttpEntity<>(headers);

      // Get the last sync token if available
      String since = syncTokenCache.getOrDefault(username, "");

      // Build sync URL with room filter
      String filter =
          java.net.URLEncoder.encode(
              "{\"room\":{\"timeline\":{\"limit\":50},\"rooms\":[\"" + roomId + "\"]}}",
              java.nio.charset.StandardCharsets.UTF_8);

      String url =
          matrixConfig.getApiUrl(ENDPOINT_SYNC)
              + "?timeout="
              + timeout
              + (since.isEmpty() ? "" : "&since=" + since)
              + "&filter="
              + filter;

      log.info("Syncing Matrix room: {} for user: {} (timeout: {}ms)", roomId, username, timeout);

      var response =
          restTemplate.exchange(
              url, org.springframework.http.HttpMethod.GET, request, java.util.Map.class);

      if (response.getBody() != null) {
        // Extract next_batch token for future syncs
        String nextBatch = (String) response.getBody().get("next_batch");
        if (nextBatch != null) {
          syncTokenCache.put(username, nextBatch);
        }

        // Extract messages from the room
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> rooms =
            (java.util.Map<String, Object>) response.getBody().get("rooms");

        if (rooms != null) {
          @SuppressWarnings("unchecked")
          java.util.Map<String, Object> join = (java.util.Map<String, Object>) rooms.get("join");

          if (join != null && join.containsKey(roomId)) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> roomData =
                (java.util.Map<String, Object>) join.get(roomId);

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> timeline =
                (java.util.Map<String, Object>) roomData.get("timeline");

            if (timeline != null && timeline.containsKey("events")) {
              @SuppressWarnings("unchecked")
              java.util.List<java.util.Map<String, Object>> events =
                  (java.util.List<java.util.Map<String, Object>>) timeline.get("events");

              // Filter for text messages
              java.util.List<java.util.Map<String, Object>> messages =
                  events.stream()
                      .filter(event -> "m.room.message".equals(event.get("type")))
                      .filter(
                          event -> {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> content =
                                (java.util.Map<String, Object>) event.get("content");
                            return content != null && "m.text".equals(content.get("msgtype"));
                          })
                      .collect(java.util.stream.Collectors.toList());

              return java.util.Map.of(
                  "messages", messages, "next_batch", nextBatch != null ? nextBatch : "");
            }
          }
        }
      }

      return java.util.Map.of("messages", new java.util.ArrayList<>(), "next_batch", "");
    } catch (Exception ex) {
      log.error("Matrix Error: Could not sync room ({}). Reason: {}", roomId, ex.getMessage());
      return java.util.Map.of("messages", new java.util.ArrayList<>(), "next_batch", "");
    }
  }

  /**
   * Gets HTTP headers for admin API calls.
   *
   * @return the HTTP headers
   */
  private HttpHeaders getAdminHttpHeaders() {
    var headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + matrixConfig.getRegistrationSharedSecret());
    return headers;
  }

  /**
   * Gets HTTP headers for client API calls.
   *
   * @param accessToken the access token
   * @return the HTTP headers
   */
  private HttpHeaders getClientHttpHeaders(String accessToken) {
    var headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + accessToken);
    return headers;
  }

  /**
   * Extracts nonce from Matrix response.
   *
   * @param responseBody the response body
   * @return the nonce or null if not found
   */
  private String extractNonceFromResponse(String responseBody) {
    try {
      if (responseBody != null && responseBody.contains("\"nonce\"")) {
        // Simple JSON parsing to extract nonce
        int nonceStart = responseBody.indexOf("\"nonce\":\"") + 9;
        int nonceEnd = responseBody.indexOf("\"", nonceStart);
        if (nonceStart > 8 && nonceEnd > nonceStart) {
          return responseBody.substring(nonceStart, nonceEnd);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to extract nonce from response: {}", e.getMessage());
    }
    return null;
  }

  /**
   * Generates HMAC-SHA1 MAC for Matrix user registration.
   *
   * @param nonce the nonce from Matrix
   * @param username the username
   * @param password the password
   * @param admin whether the user is an admin
   * @return the generated MAC
   * @throws MatrixCreateUserException if MAC generation fails
   */
  private String generateMac(String nonce, String username, String password, boolean admin)
      throws MatrixCreateUserException {
    try {
      // Construct the message to sign: nonce + "\0" + username + "\0" + password + "\0" + (admin ?
      // "admin" : "notadmin")
      String message =
          nonce + "\0" + username + "\0" + password + "\0" + (admin ? "admin" : "notadmin");

      // Get the registration shared secret
      String sharedSecret = matrixConfig.getRegistrationSharedSecret();

      // Create HMAC-SHA1 instance
      Mac hmacSha1 = Mac.getInstance("HmacSHA1");
      SecretKeySpec secretKey =
          new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
      hmacSha1.init(secretKey);

      // Calculate MAC
      byte[] macBytes = hmacSha1.doFinal(message.getBytes(StandardCharsets.UTF_8));

      // Convert to hex string
      return Hex.encodeHexString(macBytes);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      log.error("Failed to generate MAC for Matrix user registration", e);
      throw new MatrixCreateUserException("Failed to generate MAC: " + e.getMessage());
    }
  }

  /**
   * Upload a file to Matrix media repository and send as message.
   *
   * @param file the file to upload
   * @param roomId the room to send the message to
   * @param accessToken the Matrix access token
   * @return Map with content_uri and upload success
   */
  public java.util.Map<String, Object> uploadFile(
      MultipartFile file, String roomId, String accessToken) {
    try {
      String url = matrixConfig.getApiUrl(ENDPOINT_MEDIA_UPLOAD);

      log.info(
          "📤 Uploading file to Matrix: {} ({}bytes)", file.getOriginalFilename(), file.getSize());

      HttpHeaders headers = new HttpHeaders();
      headers.set("Authorization", "Bearer " + accessToken);
      headers.setContentType(MediaType.parseMediaType(file.getContentType()));

      HttpEntity<byte[]> requestEntity = new HttpEntity<>(file.getBytes(), headers);

      ResponseEntity<java.util.Map> response =
          restTemplate.postForEntity(url, requestEntity, java.util.Map.class);

      if (response.getBody() != null && response.getBody().containsKey("content_uri")) {
        String contentUri = (String) response.getBody().get("content_uri");
        log.info("✅ File uploaded successfully: {}", contentUri);

        // Automatically send file message to room
        sendFileMessage(
            roomId,
            file.getOriginalFilename(),
            contentUri,
            file.getContentType(),
            file.getSize(),
            accessToken);

        return java.util.Map.of(
            "success",
            true,
            "content_uri",
            contentUri,
            "file_name",
            file.getOriginalFilename(),
            "file_size",
            file.getSize());
      }

      throw new RuntimeException("No content_uri in Matrix upload response");

    } catch (Exception e) {
      log.error("❌ Failed to upload file to Matrix", e);
      throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
    }
  }

  /**
   * Download a file from Matrix media repository.
   *
   * @param serverName the Matrix server name
   * @param mediaId the media ID
   * @param accessToken the Matrix access token
   * @return the file bytes
   */
  public byte[] downloadFile(String serverName, String mediaId, String accessToken) {
    try {
      String url =
          matrixConfig.getApiUrl("/_matrix/media/r0/download/" + serverName + "/" + mediaId);

      log.info("📥 Downloading file from Matrix: {}/{}", serverName, mediaId);

      HttpHeaders headers = new HttpHeaders();
      headers.set("Authorization", "Bearer " + accessToken);

      HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

      ResponseEntity<byte[]> response =
          restTemplate.exchange(
              url, org.springframework.http.HttpMethod.GET, requestEntity, byte[].class);

      if (response.getBody() != null) {
        log.info("✅ File downloaded successfully: {} bytes", response.getBody().length);
        return response.getBody();
      }

      throw new RuntimeException("No file data in Matrix download response");

    } catch (Exception e) {
      log.error("❌ Failed to download file from Matrix", e);
      throw new RuntimeException("Failed to download file: " + e.getMessage(), e);
    }
  }

  /**
   * Send a file message to a Matrix room.
   *
   * @param roomId the room ID
   * @param fileName the file name
   * @param contentUri the Matrix content URI (mxc://...)
   * @param mimeType the file MIME type
   * @param fileSize the file size in bytes
   * @param accessToken the access token
   * @return the send response with event_id
   */
  private java.util.Map<String, Object> sendFileMessage(
      String roomId,
      String fileName,
      String contentUri,
      String mimeType,
      long fileSize,
      String accessToken) {
    try {
      var headers = getClientHttpHeaders(accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      // Determine message type based on MIME type
      String msgtype = "m.file";
      if (mimeType != null) {
        if (mimeType.startsWith("image/")) {
          msgtype = "m.image";
        } else if (mimeType.startsWith("video/")) {
          msgtype = "m.video";
        } else if (mimeType.startsWith("audio/")) {
          msgtype = "m.audio";
        }
      }

      var messageBody = new java.util.HashMap<String, Object>();
      messageBody.put("msgtype", msgtype);
      messageBody.put("body", fileName);
      messageBody.put("url", contentUri);

      var info = new java.util.HashMap<String, Object>();
      info.put("size", fileSize);
      if (mimeType != null) {
        info.put("mimetype", mimeType);
      }
      messageBody.put("info", info);

      HttpEntity<java.util.Map<String, Object>> request = new HttpEntity<>(messageBody, headers);

      String txnId = java.util.UUID.randomUUID().toString();
      var url =
          matrixConfig.getApiUrl(
              "/_matrix/client/r0/rooms/" + roomId + "/send/m.room.message/" + txnId);

      log.info("📨 Sending file message to Matrix room: {} (type: {})", roomId, msgtype);

      var response =
          restTemplate.exchange(
              url, org.springframework.http.HttpMethod.PUT, request, java.util.Map.class);

      log.info("✅ File message sent successfully");
      return response.getBody();
    } catch (Exception ex) {
      log.error(
          "Matrix Error: Could not send file message to room ({}). Reason: {}",
          roomId,
          ex.getMessage());
      return java.util.Map.of("error", ex.getMessage());
    }
  }

  /**
   * Make a generic Matrix API request. Used by MatrixEventListenerService for /sync calls.
   *
   * @param url the full Matrix API URL
   * @param method the HTTP method (GET, POST, PUT, etc.)
   * @param accessToken the Matrix access token
   * @param body the request body (can be null for GET requests)
   * @return the response body as a Map
   */
  @SuppressWarnings("unchecked")
  public java.util.Map<String, Object> makeMatrixRequest(
      String url, String method, String accessToken, java.util.Map<String, Object> body) {
    try {
      org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
      headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
      headers.setBearerAuth(accessToken);

      HttpEntity<?> request;
      if (body != null) {
        request = new HttpEntity<>(body, headers);
      } else {
        request = new HttpEntity<>(headers);
      }

      org.springframework.http.HttpMethod httpMethod;
      switch (method.toUpperCase()) {
        case "POST":
          httpMethod = org.springframework.http.HttpMethod.POST;
          break;
        case "PUT":
          httpMethod = org.springframework.http.HttpMethod.PUT;
          break;
        case "DELETE":
          httpMethod = org.springframework.http.HttpMethod.DELETE;
          break;
        default:
          httpMethod = org.springframework.http.HttpMethod.GET;
      }

      var response = restTemplate.exchange(url, httpMethod, request, java.util.Map.class);

      return response.getBody();
    } catch (Exception ex) {
      log.error("Matrix Error: Request to {} failed. Reason: {}", url, ex.getMessage());
      return null;
    }
  }

  /**
   * Get the Matrix API base URL for constructing endpoints.
   *
   * @return the Matrix API URL
   */
  public String getMatrixApiUrl() {
    return matrixConfig.getApiUrl("");
  }

  /**
   * Get the list of room IDs that a user has joined.
   *
   * @param username the Matrix username (without @)
   * @param password the Matrix password
   * @return list of joined room IDs
   */
  public java.util.List<String> getJoinedRooms(String username, String password) {
    try {
      // Login to get access token
      String accessToken = loginUser(username, password);
      if (accessToken == null) {
        log.warn("Could not login Matrix user {} to get joined rooms", username);
        return java.util.Collections.emptyList();
      }

      // Call Matrix API to get joined rooms
      var headers = new HttpHeaders();
      headers.set("Authorization", "Bearer " + accessToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      var url = matrixConfig.getApiUrl(ENDPOINT_JOINED_ROOMS);
      HttpEntity<Void> request = new HttpEntity<>(headers);

      var response =
          restTemplate.exchange(
              url, org.springframework.http.HttpMethod.GET, request, java.util.Map.class);

      if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
        @SuppressWarnings("unchecked")
        java.util.List<String> joinedRooms =
            (java.util.List<String>) response.getBody().get("joined_rooms");
        log.info(
            "✅ User {} has {} joined Matrix rooms",
            username,
            joinedRooms != null ? joinedRooms.size() : 0);
        return joinedRooms != null ? joinedRooms : java.util.Collections.emptyList();
      }

      log.warn("Failed to get joined rooms for user {}: {}", username, response.getStatusCode());
      return java.util.Collections.emptyList();

    } catch (Exception e) {
      log.error("Error getting joined rooms for user {}: {}", username, e.getMessage());
      return java.util.Collections.emptyList();
    }
  }
}
