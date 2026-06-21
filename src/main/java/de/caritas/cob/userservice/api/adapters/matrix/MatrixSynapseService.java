package de.caritas.cob.userservice.api.adapters.matrix;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserResponseDTO;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/** Service for Matrix Synapse functionalities. */
@Slf4j
@Service
public class MatrixSynapseService {

  private static final long SERVER_OPERATION_TOKEN_TTL_MS = 10 * 60 * 1000L;
  private static final String ENDPOINT_REGISTER_USER = "/_synapse/admin/v1/register";
  private static final String ENDPOINT_ADMIN_LOGIN_AS_USER = "/_synapse/admin/v1/users/%s/login";
  private static final String ENDPOINT_LOGIN = "/_matrix/client/r0/login";
  private static final String ENDPOINT_SYNC = "/_matrix/client/r0/sync";
  private static final String ENDPOINT_UPDATE_USER_ADMIN = "/_synapse/admin/v2/users/{userId}";
  private static final String ENDPOINT_DEACTIVATE_USER = "/_synapse/admin/v1/deactivate/{userId}";
  private static final String ENDPOINT_PURGE_ROOM = "/_synapse/admin/v2/rooms/{roomId}";
  private static final String ENDPOINT_JOINED_ROOMS = "/_matrix/client/r0/joined_rooms";
  private static final String ENDPOINT_PRESENCE = "/_matrix/client/r0/presence/{userId}/status";
  private static final long PRESENCE_CACHE_TTL_MS = 10_000L;

  private final MatrixConfig matrixConfig;
  private final RestTemplate restTemplate;
  private final RestTemplate matrixLongPollRestTemplate;
  private final MatrixRoomClient matrixRoomClient;
  private final MatrixMediaClient matrixMediaClient;

  // Cache for Matrix access tokens (username -> access token)
  private final java.util.Map<String, String> accessTokenCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  // Cache for Matrix sync tokens (username -> next_batch token)
  private final java.util.Map<String, String> syncTokenCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  // Short-lived cache of user presence (matrixUserId -> state + expiry) so the
  // polled
  // availability endpoint does not hit Synapse once per consultant on every
  // request.
  private final java.util.Map<String, CachedPresence> presenceCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static final class CachedPresence {
    private final String state;
    private final boolean available;
    private final long expiresAt;

    private CachedPresence(String state, boolean available, long expiresAt) {
      this.state = state;
      this.available = available;
      this.expiresAt = expiresAt;
    }
  }

  // Cached admin access token for admin operations
  private String cachedAdminToken = null;
  private long adminTokenExpiry = 0;

  // Short-lived impersonation tokens obtained via Synapse admin API (matrixUserId
  // -> token)
  private final java.util.Map<String, CachedImpersonationToken> impersonationTokenCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static final long IMPERSONATION_TOKEN_TTL_MS = 50 * 60 * 1000L;

  private static final class CachedImpersonationToken {
    private final String token;
    private final long expiryMs;

    private CachedImpersonationToken(String token, long expiryMs) {
      this.token = token;
      this.expiryMs = expiryMs;
    }
  }

  public MatrixSynapseService(
      MatrixConfig matrixConfig,
      RestTemplate restTemplate,
      @Qualifier("matrixLongPollRestTemplate") RestTemplate matrixLongPollRestTemplate,
      MatrixRoomClient matrixRoomClient,
      MatrixMediaClient matrixMediaClient) {
    this.matrixConfig = matrixConfig;
    this.restTemplate = restTemplate;
    this.matrixLongPollRestTemplate = matrixLongPollRestTemplate;
    this.matrixRoomClient = matrixRoomClient;
    this.matrixMediaClient = matrixMediaClient;
  }

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
   * Creates a short-lived Matrix access token for a user via the Synapse admin API.
   *
   * @param matrixUserId full Matrix user ID, e.g. {@code @user:server}
   * @param validForMs validity duration in milliseconds
   * @return login response body, including {@code access_token}, or {@code null} when unavailable
   */
  public java.util.Map<String, Object> loginAsUser(String matrixUserId, long validForMs) {
    if (matrixUserId == null || matrixUserId.isBlank()) {
      return null;
    }

    String adminToken = getAdminAccessToken();
    if (adminToken == null) {
      log.warn("Matrix admin token unavailable; cannot create user token for {}", matrixUserId);
      return null;
    }

    try {
      String url =
          matrixConfig.getApiUrl(String.format(ENDPOINT_ADMIN_LOGIN_AS_USER, matrixUserId));

      var headers = getClientHttpHeaders(adminToken);
      headers.setContentType(MediaType.APPLICATION_JSON);

      var body = new java.util.HashMap<String, Object>();
      if (validForMs > 0) {
        body.put("valid_until_ms", System.currentTimeMillis() + validForMs);
      }

      HttpEntity<java.util.Map<String, Object>> request = new HttpEntity<>(body, headers);

      var response = restTemplate.postForEntity(url, request, java.util.Map.class);

      @SuppressWarnings("unchecked")
      java.util.Map<String, Object> responseBody =
          response.getBody() == null
              ? java.util.Map.of()
              : (java.util.Map<String, Object>) response.getBody();
      return responseBody;
    } catch (HttpStatusCodeException ex) {
      log.error(
          "Matrix Error: Could not create login token for user ({}). Status: {}, Response: {}",
          matrixUserId,
          ex.getStatusCode(),
          ex.getResponseBodyAsString());
      return null;
    } catch (Exception ex) {
      log.error(
          "Matrix Error: Could not create login token for user ({}). Reason: {}",
          matrixUserId,
          ex.getMessage());
      return null;
    }
  }

  public String loginAsUserAccessToken(String matrixUserId) {
    var tokenResponse = loginAsUser(matrixUserId, SERVER_OPERATION_TOKEN_TTL_MS);
    if (tokenResponse == null || tokenResponse.get("access_token") == null) {
      return null;
    }
    return String.valueOf(tokenResponse.get("access_token"));
  }

  /**
   * Gets or creates an admin access token for administrative operations. Creates a technical admin
   * user if it doesn't exist.
   *
   * @return admin access token, or null if failed
   */
  private String getAdminAccessToken() {
    // Check if cached token is still valid (expires in 1 hour, we refresh after 50
    // minutes)
    long now = System.currentTimeMillis();
    if (cachedAdminToken != null && now < adminTokenExpiry) {
      return cachedAdminToken;
    }

    try {
      String adminUsername = matrixConfig.getAdminUsername();
      String adminPassword = matrixConfig.getAdminPassword();
      if (adminUsername == null
          || adminUsername.isBlank()
          || adminPassword == null
          || adminPassword.isBlank()) {
        log.warn("Matrix admin credentials are not configured; skipping privileged sync login.");
        return null;
      }

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
   * Deactivates and erases a Matrix user via the Synapse admin API.
   *
   * @param matrixUserId the full Matrix user ID (e.g., @username:domain)
   * @return true if successful, false otherwise
   */
  public boolean deactivateUser(String matrixUserId) {
    try {
      String adminToken = getAdminToken();
      if (adminToken == null) {
        log.warn("Could not get admin token for Matrix user deactivation");
        return false;
      }

      String url =
          matrixConfig.getApiUrl(ENDPOINT_DEACTIVATE_USER.replace("{userId}", matrixUserId));

      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(adminToken);

      var body = new java.util.HashMap<String, Object>();
      body.put("erase", true);

      var request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.exchange(
              url, org.springframework.http.HttpMethod.POST, request, String.class);

      log.info("Successfully deactivated Matrix user: {}", matrixUserId);
      return response.getStatusCode().is2xxSuccessful();

    } catch (Exception ex) {
      log.warn("Failed to deactivate Matrix user {}: {}", matrixUserId, ex.getMessage());
      return false;
    }
  }

  /**
   * Purges a Matrix room and its message history via the Synapse admin API.
   *
   * @param matrixRoomId the Matrix room ID
   * @return true if successful, false otherwise
   */
  public boolean purgeRoom(String matrixRoomId) {
    try {
      String adminToken = getAdminToken();
      if (adminToken == null) {
        log.warn("Could not get admin token for Matrix room purge");
        return false;
      }

      String url = matrixConfig.getApiUrl(ENDPOINT_PURGE_ROOM.replace("{roomId}", matrixRoomId));

      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(adminToken);

      var body = new java.util.HashMap<String, Object>();
      body.put("purge", true);

      var request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.exchange(
              url, org.springframework.http.HttpMethod.DELETE, request, String.class);

      log.info("Successfully purged Matrix room: {}", matrixRoomId);
      return response.getStatusCode().is2xxSuccessful();

    } catch (Exception ex) {
      log.warn("Failed to purge Matrix room {}: {}", matrixRoomId, ex.getMessage());
      return false;
    }
  }

  /**
   * Obtains a short-lived Matrix access token for {@code matrixUserId} via the Synapse admin API,
   * without requiring knowledge of the user's password.
   *
   * @param matrixUserId full Matrix user ID (e.g. {@code @username:domain})
   * @return access token, or null if admin impersonation failed
   */
  public String loginUserViaAdmin(String matrixUserId) {
    if (matrixUserId == null
        || matrixUserId.isBlank()
        || !matrixUserId.startsWith("@")
        || !matrixUserId.contains(":")) {
      return null;
    }

    long now = System.currentTimeMillis();
    impersonationTokenCache.entrySet().removeIf(e -> now >= e.getValue().expiryMs);

    CachedImpersonationToken cached = impersonationTokenCache.get(matrixUserId);
    if (cached != null) {
      return cached.token;
    }

    String adminToken = getAdminAccessToken();
    if (adminToken == null) {
      log.warn("Cannot impersonate Matrix user {} without admin token", matrixUserId);
      return null;
    }

    try {
      String encodedUserId =
          java.net.URLEncoder.encode(matrixUserId, StandardCharsets.UTF_8).replace("+", "%20");
      String url =
          matrixConfig.getApiUrl(ENDPOINT_ADMIN_USER_LOGIN.replace("{userId}", encodedUserId));

      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(adminToken);

      var response =
          restTemplate.postForEntity(
              url, new HttpEntity<>(java.util.Map.of(), headers), java.util.Map.class);

      if (response.getBody() != null && response.getBody().containsKey("access_token")) {
        String accessToken = (String) response.getBody().get("access_token");
        impersonationTokenCache.put(
            matrixUserId,
            new CachedImpersonationToken(accessToken, now + IMPERSONATION_TOKEN_TTL_MS));
        log.debug("Obtained admin impersonation token for Matrix user {}", matrixUserId);
        return accessToken;
      }

      log.error(
          "Matrix admin impersonation login failed for user {} - no access token", matrixUserId);
      return null;
    } catch (Exception ex) {
      log.error(
          "Matrix admin impersonation login failed for user {}: {}", matrixUserId, ex.getMessage());
      return null;
    }
  }

  /**
   * Logs in a Matrix user with username/password and returns access token.
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

  public ResponseEntity<MatrixCreateRoomResponseDTO> createRoomAsMatrixUser(
      String roomName, String roomAlias, String matrixUserId) throws MatrixCreateRoomException {

    String accessToken = loginAsUserAccessToken(matrixUserId);
    if (accessToken == null) {
      throw new MatrixCreateRoomException("Could not obtain access token for Matrix user");
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

    return matrixRoomClient.createRoom(roomName, roomAlias, accessToken);
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

    return matrixRoomClient.inviteUserToRoom(roomId, userId, accessToken);
  }

  /**
   * Accepts a room invitation (joins the room) on behalf of a user.
   *
   * @param roomId the room ID to join
   * @param accessToken the access token of the user who was invited
   * @return true if successful, false otherwise
   */
  public boolean joinRoom(String roomId, String accessToken) {
    return matrixRoomClient.joinRoom(roomId, accessToken);
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
          matrixLongPollRestTemplate.exchange(
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
          matrixLongPollRestTemplate.exchange(
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
      // Construct the message to sign: nonce + "\0" + username + "\0" + password +
      // "\0" + (admin ?
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
      org.springframework.web.multipart.MultipartFile file, String roomId, String accessToken) {
    return matrixMediaClient.uploadFile(file, roomId, accessToken);
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
    return matrixMediaClient.downloadFile(serverName, mediaId, accessToken);
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

      var response =
          matrixLongPollRestTemplate.exchange(url, httpMethod, request, java.util.Map.class);

      return response.getBody();
    } catch (Exception ex) {
      log.error(
          "Matrix Error: Request to {} failed. Reason: {}",
          sanitizeUrlForLog(url),
          ex.getMessage());
      return null;
    }
  }

  private String sanitizeUrlForLog(String url) {
    if (url == null) {
      return "null";
    }
    int queryStart = url.indexOf('?');
    return queryStart >= 0 ? url.substring(0, queryStart) : url;
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

  public java.util.List<String> getJoinedRoomsForMatrixUser(String matrixUserId) {
    String accessToken = loginAsUserAccessToken(matrixUserId);
    if (accessToken == null) {
      log.warn("Could not create Matrix token for {} to get joined rooms", matrixUserId);
      return java.util.Collections.emptyList();
    }
    return getJoinedRoomsWithToken(accessToken, matrixUserId);
  }

  private java.util.List<String> getJoinedRoomsWithToken(
      String accessToken, String principalForLog) {
    try {
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
            principalForLog,
            joinedRooms != null ? joinedRooms.size() : 0);
        return joinedRooms != null ? joinedRooms : java.util.Collections.emptyList();
      }

      log.warn(
          "Failed to get joined rooms for user {}: {}", principalForLog, response.getStatusCode());
      return java.util.Collections.emptyList();

    } catch (Exception e) {
      log.error("Error getting joined rooms for user {}: {}", principalForLog, e.getMessage());
      return java.util.Collections.emptyList();
    }
  }

  /**
   * Sets the power level (permissions) for a user in a Matrix room. Power level 100 = admin, 50 =
   * moderator, 0 = default user
   *
   * @param roomId The Matrix room ID
   * @param userId The Matrix user ID (e.g., @user:domain.com)
   * @param powerLevel The power level (0-100)
   * @param accessToken Access token of user with permission to set power levels
   * @return true if successful, false otherwise
   */
  public boolean setUserPowerLevel(
      String roomId, String userId, int powerLevel, String accessToken) {
    return matrixRoomClient.setUserPowerLevel(roomId, userId, powerLevel, accessToken);
  }

  /**
   * Removes a user from a Matrix room (kick/leave).
   *
   * @param roomId The Matrix room ID
   * @param userId The Matrix user ID to remove
   * @param accessToken Access token of user with permission to remove users
   * @return true if successful, false otherwise
   */
  public boolean removeUserFromRoom(String roomId, String userId, String accessToken) {
    return matrixRoomClient.removeUserFromRoom(roomId, userId, accessToken);
  }

  /**
   * Reads a single user's Matrix presence state ("online", "unavailable", "offline").
   *
   * @param matrixUserId the full Matrix user ID (e.g. {@code @user:domain})
   * @return the presence state, or empty when the lookup could not be performed (no admin token,
   *     presence disabled on the homeserver, or the request failed)
   */
  public java.util.Optional<String> getUserPresence(String matrixUserId) {
    String adminToken = getAdminAccessToken();
    if (adminToken == null) {
      return java.util.Optional.empty();
    }
    return readPresence(matrixUserId, adminToken).map(presence -> presence.state);
  }

  /**
   * Reads a user's presence and computes whether they are currently available, based on activity
   * recency rather than the (sticky) presence string alone.
   */
  private java.util.Optional<CachedPresence> readPresence(String matrixUserId, String adminToken) {
    if (matrixUserId == null || matrixUserId.isBlank()) {
      return java.util.Optional.empty();
    }

    long now = System.currentTimeMillis();
    CachedPresence cached = presenceCache.get(matrixUserId);
    if (cached != null && now < cached.expiresAt) {
      return java.util.Optional.of(cached);
    }

    try {
      String url = matrixConfig.getApiUrl(ENDPOINT_PRESENCE.replace("{userId}", matrixUserId));
      HttpEntity<Void> request = new HttpEntity<>(getClientHttpHeaders(adminToken));

      var response =
          restTemplate.exchange(
              url, org.springframework.http.HttpMethod.GET, request, java.util.Map.class);

      var body = response.getBody();
      if (body != null && body.get("presence") != null) {
        String state = String.valueOf(body.get("presence"));
        Long lastActiveAgo = asLong(body.get("last_active_ago"));
        boolean currentlyActive = Boolean.TRUE.equals(body.get("currently_active"));
        boolean available = computeAvailable(state, currentlyActive, lastActiveAgo);
        var presence = new CachedPresence(state, available, now + PRESENCE_CACHE_TTL_MS);
        // Cache only successful lookups; failures stay uncached so they retry next poll
        // (preserving the "every lookup failed -> fall back" semantics in callers).
        presenceCache.put(matrixUserId, presence);
        return java.util.Optional.of(presence);
      }
      return java.util.Optional.empty();
    } catch (Exception ex) {
      log.warn("Matrix presence lookup failed for {}: {}", matrixUserId, ex.getMessage());
      return java.util.Optional.empty();
    }
  }

  /**
   * A consultant is treated as available when actively connected, or when their last Matrix
   * activity is recent enough. {@code offline} is never available, and a lingering {@code
   * online}/{@code unavailable} with stale {@code last_active_ago} is correctly excluded.
   */
  private boolean computeAvailable(String state, boolean currentlyActive, Long lastActiveAgo) {
    if ("offline".equals(state)) {
      return false;
    }
    if (currentlyActive) {
      return true;
    }
    if (lastActiveAgo != null) {
      return lastActiveAgo <= matrixConfig.getPresenceActiveThresholdMs();
    }
    // No activity timestamp from Synapse — only trust an explicit "online".
    return "online".equals(state);
  }

  private Long asLong(Object value) {
    return value instanceof Number ? ((Number) value).longValue() : null;
  }

  /**
   * Returns the subset of the given Matrix user IDs whose presence indicates an active client
   * (online, or idle-but-connected "unavailable").
   *
   * <p>Returns {@link java.util.Optional#empty()} when Matrix presence cannot be determined at all
   * — presence disabled via {@code matrix.presenceEnabled}, no admin token, or every lookup failed
   * — so callers can fall back to another availability signal instead of wrongly assuming everyone
   * is offline. A present (possibly empty) set is an authoritative answer.
   *
   * @param matrixUserIds the candidate Matrix user IDs to check
   * @return online subset, or empty when presence is unavailable
   */
  public java.util.Optional<java.util.Set<String>> findOnlineMatrixUserIds(
      java.util.Collection<String> matrixUserIds) {
    if (!matrixConfig.isPresenceEnabled()) {
      log.debug(
          "Matrix presence disabled (matrix.presenceEnabled=false); availability unavailable");
      return java.util.Optional.empty();
    }
    String adminToken = getAdminAccessToken();
    if (adminToken == null) {
      log.warn("Matrix admin token unavailable; cannot determine consultant presence");
      return java.util.Optional.empty();
    }
    if (matrixUserIds == null || matrixUserIds.isEmpty()) {
      return java.util.Optional.of(java.util.Set.of());
    }

    java.util.Set<String> online = new java.util.HashSet<>();
    int resolved = 0;
    for (String matrixUserId : matrixUserIds) {
      if (matrixUserId == null || matrixUserId.isBlank()) {
        continue;
      }
      var presence = readPresence(matrixUserId, adminToken);
      if (presence.isEmpty()) {
        continue;
      }
      resolved++;
      if (presence.get().available) {
        online.add(matrixUserId);
      }
    }

    log.info(
        "Matrix presence: candidates={}, resolved={}, available={}",
        matrixUserIds.size(),
        resolved,
        online.size());

    // No lookup succeeded -> presence genuinely unavailable; let callers fall back.
    return resolved > 0 ? java.util.Optional.of(online) : java.util.Optional.empty();
  }
}
