package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

@ExtendWith(MockitoExtension.class)
class MatrixSynapseServiceTest {

  private static final String MATRIX_USER_ID = "@seeker:matrix.example.com";
  private static final String MATRIX_ROOM_ID = "!room:matrix.example.com";
  private static final String ADMIN_TOKEN = "admin-token";
  private static final String IMPERSONATION_TOKEN = "syt_admin_impersonation_token";
  private static final String SYNC_URL = "https://matrix.example/_matrix/client/r0/sync";
  private static final String ACCESS_TOKEN = "access-token";
  private static final String MATRIX_BASE_URL = "https://matrix.example";
  private static final String MATRIX_ADMIN_TOKEN = "admin-token";
  private static final String MATRIX_USER_TOKEN = "user-token";
  private static final String REGISTER_URL =
      "https://matrix.example.com/_synapse/admin/v1/register";
  private static final String LOGIN_URL = "https://matrix.example.com/_matrix/client/r0/login";
  private static final String REGISTRATION_SECRET = "caritas-registration-secret-2025";

  private MatrixConfig matrixConfig;
  @Mock private RestTemplate restTemplate;
  @Mock private RestTemplate matrixLongPollRestTemplate;
  @Mock private MatrixRoomClient matrixRoomClient;
  @Mock private MatrixMediaClient matrixMediaClient;

  @BeforeEach
  void setUpMatrixConfig() {
    matrixConfig = new MatrixConfig();
    matrixConfig.setApiUrl("https://matrix.example.com");
    matrixConfig.setRegistrationSharedSecret(REGISTRATION_SECRET);
  }

  @Test
  void makeMatrixRequestShouldUseDedicatedLongPollRestTemplate() {
    var responseBody = Map.<String, Object>of("next_batch", "sync-token");
    when(matrixLongPollRestTemplate.exchange(
            eq(SYNC_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(responseBody));
    var service = matrixSynapseService();

    var result = service.makeMatrixRequest(SYNC_URL, "GET", ACCESS_TOKEN, null);

    assertThat(result).isSameAs(responseBody);
    verify(matrixLongPollRestTemplate)
        .exchange(eq(SYNC_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    verifyNoInteractions(restTemplate);
  }

  @Test
  void makeMatrixRequestShouldPassBearerTokenToLongPollRestTemplate() {
    when(matrixLongPollRestTemplate.exchange(
            eq(SYNC_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()));
    var requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    var service = matrixSynapseService();

    service.makeMatrixRequest(SYNC_URL, "GET", ACCESS_TOKEN, null);

    verify(matrixLongPollRestTemplate)
        .exchange(eq(SYNC_URL), eq(HttpMethod.GET), requestCaptor.capture(), eq(Map.class));
    assertThat(requestCaptor.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer " + ACCESS_TOKEN);
  }

  // Regression test for the restored long-poll room sync.
  //
  // MatrixSynapseService.syncRoom builds its URL via MatrixUrlBuilder.buildUrl(...). The "filter"
  // query param is a JSON string ({"room":{"timeline":...}}). MatrixUrlBuilder now expands the path
  // template vars first and then adds the query params with UriUtils.encodeQueryParam +
  // build(true),
  // so the embedded {"room"...} braces are no longer mistaken for URI-template variables. As a
  // result the dedicated long-poll RestTemplate is actually invoked and messages are fetched.
  @Test
  void syncRoomShouldUseDedicatedLongPollRestTemplate() {
    var service = matrixSynapseService();
    var roomId = "!room:example.org";
    matrixConfig.setApiUrl("https://matrix.example");
    var textMessage =
        Map.<String, Object>of(
            "type", "m.room.message", "content", Map.of("msgtype", "m.text", "body", "hello"));
    var timeline = Map.<String, Object>of("events", java.util.List.of(textMessage));
    var roomData = Map.<String, Object>of("timeline", timeline);
    var join = Map.<String, Object>of(roomId, roomData);
    var rooms = Map.<String, Object>of("join", join);
    var responseBody = Map.<String, Object>of("next_batch", "s_token_42", "rooms", rooms);
    when(matrixLongPollRestTemplate.exchange(
            any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(responseBody));
    var urlCaptor = ArgumentCaptor.forClass(String.class);

    var result = service.syncRoom(roomId, ACCESS_TOKEN, "alice", 30000);

    verify(matrixLongPollRestTemplate)
        .exchange(urlCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    assertThat(urlCaptor.getValue()).startsWith(SYNC_URL + "?");
    assertThat(urlCaptor.getValue()).contains("timeout=30000");
    // The JSON filter is URL-encoded (its braces survive as %7B/%7D) and the room id is present.
    assertThat(urlCaptor.getValue()).contains("filter=");
    assertThat(urlCaptor.getValue()).contains("timeline");
    assertThat(urlCaptor.getValue())
        .contains(UriUtils.encodeQueryParam(roomId, StandardCharsets.UTF_8));
    // The parsed result reflects the body: the next_batch token and the single text message.
    assertThat(result).isNotNull();
    assertThat(result).containsEntry("next_batch", "s_token_42");
    @SuppressWarnings("unchecked")
    var messages = (java.util.List<Map<String, Object>>) result.get("messages");
    assertThat(messages).hasSize(1);
    assertThat(messages.get(0)).isEqualTo(textMessage);
    verifyNoInteractions(restTemplate);
  }

  @Test
  void getRoomMessagesShouldUseDedicatedLongPollRestTemplate() {
    var service = matrixSynapseService();
    var roomId = "!room:example.org";
    matrixConfig.setApiUrl("https://matrix.example");
    when(matrixLongPollRestTemplate.exchange(
            any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("chunk", java.util.List.of())));
    var urlCaptor = ArgumentCaptor.forClass(String.class);

    var result = service.getRoomMessages(roomId, ACCESS_TOKEN);

    assertThat(result).isNotNull().isEmpty();
    verify(matrixLongPollRestTemplate)
        .exchange(urlCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    assertThat(urlCaptor.getValue())
        .startsWith(
            "https://matrix.example/_matrix/client/r0/rooms/%21room%3Aexample.org/messages?");
    assertThat(urlCaptor.getValue()).contains("dir=b");
    assertThat(urlCaptor.getValue()).contains("limit=100");
    verifyNoInteractions(restTemplate);
  }

  @Test
  void deactivateUserShouldReturnTrueWhenSynapseAdminApiSucceeds() {
    stubAdminLogin();
    when(restTemplate.exchange(
            eq(
                "https://matrix.example.com/_synapse/admin/v1/deactivate/"
                    + "%40seeker%3Amatrix.example.com"),
            eq(HttpMethod.POST),
            any(HttpEntity.class),
            eq(String.class)))
        .thenReturn(ResponseEntity.ok(""));

    assertThat(matrixSynapseService().deactivateUser(MATRIX_USER_ID)).isTrue();
  }

  @Test
  void deactivateUserShouldReturnFalseWhenSynapseReturnsServiceUnavailable() {
    stubAdminLogin();
    when(restTemplate.exchange(
            eq(
                "https://matrix.example.com/_synapse/admin/v1/deactivate/"
                    + "%40seeker%3Amatrix.example.com"),
            eq(HttpMethod.POST),
            any(HttpEntity.class),
            eq(String.class)))
        .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

    assertThat(matrixSynapseService().deactivateUser(MATRIX_USER_ID)).isFalse();
  }

  @Test
  void deactivateUserShouldReturnFalseWhenAdminTokenMissing() {
    matrixConfig.setAdminUsername("");
    matrixConfig.setAdminPassword("");

    assertThat(matrixSynapseService().deactivateUser(MATRIX_USER_ID)).isFalse();
    verifyNoInteractions(restTemplate);
  }

  @Test
  void purgeRoomShouldReturnTrueWhenSynapseAdminApiSucceeds() {
    stubAdminLogin();
    when(restTemplate.exchange(
            eq("https://matrix.example.com/_synapse/admin/v2/rooms/%21room%3Amatrix.example.com"),
            eq(HttpMethod.DELETE),
            any(HttpEntity.class),
            eq(String.class)))
        .thenReturn(ResponseEntity.ok(""));

    assertThat(matrixSynapseService().purgeRoom(MATRIX_ROOM_ID)).isTrue();
  }

  @Test
  void purgeRoomShouldReturnFalseWhenSynapseReturnsServiceUnavailable() {
    stubAdminLogin();
    when(restTemplate.exchange(
            eq("https://matrix.example.com/_synapse/admin/v2/rooms/%21room%3Amatrix.example.com"),
            eq(HttpMethod.DELETE),
            any(HttpEntity.class),
            eq(String.class)))
        .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

    assertThat(matrixSynapseService().purgeRoom(MATRIX_ROOM_ID)).isFalse();
  }

  private void stubAdminLogin() {
    matrixConfig.setAdminUsername("admin");
    matrixConfig.setAdminPassword("admin-password");
    when(restTemplate.postForEntity(
            eq("https://matrix.example.com/_matrix/client/r0/login"),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", ADMIN_TOKEN)));
  }

  @Test
  void loginAsUserShouldEncodeMatrixUserIdOnce() {
    var expectedUri =
        URI.create(MATRIX_BASE_URL + "/_synapse/admin/v1/users/%40alice%3Aexample.org/login");
    stubAdminLogin(expectedUri);
    var service = matrixSynapseService();

    var accessToken = service.loginAsUserAccessToken("@alice:example.org");

    assertThat(accessToken).isEqualTo(MATRIX_USER_TOKEN);
    verify(restTemplate).postForEntity(eq(expectedUri), any(HttpEntity.class), eq(Map.class));
  }

  @Test
  void loginAsUserShouldNotDoubleEncodeMatrixUserId() {
    var expectedUri =
        URI.create(MATRIX_BASE_URL + "/_synapse/admin/v1/users/%40alice%3Aexample.org/login");
    stubAdminLogin(expectedUri);
    var service = matrixSynapseService();

    var accessToken = service.loginAsUserAccessToken("%40alice%3Aexample.org");

    assertThat(accessToken).isEqualTo(MATRIX_USER_TOKEN);
    verify(restTemplate).postForEntity(eq(expectedUri), any(HttpEntity.class), eq(Map.class));
    verify(restTemplate, times(0))
        .postForEntity(
            eq(
                URI.create(
                    MATRIX_BASE_URL + "/_synapse/admin/v1/users/%2540alice%253Aexample.org/login")),
            any(HttpEntity.class),
            eq(Map.class));
  }

  @Test
  void loginUserViaAdminShouldNotDoubleEncodeMatrixUserId() {
    var expectedUri =
        URI.create(MATRIX_BASE_URL + "/_synapse/admin/v1/users/%40alice%3Aexample.org/login");
    stubAdminLogin(expectedUri);
    var service = matrixSynapseService();

    var accessToken = service.loginUserViaAdmin("@alice:example.org");

    assertThat(accessToken).isEqualTo(MATRIX_USER_TOKEN);
    verify(restTemplate).postForEntity(eq(expectedUri), any(HttpEntity.class), eq(Map.class));
    verify(restTemplate, times(0))
        .postForEntity(
            eq(
                URI.create(
                    MATRIX_BASE_URL + "/_synapse/admin/v1/users/%2540alice%253Aexample.org/login")),
            any(HttpEntity.class),
            eq(Map.class));
  }

  @Test
  void banUserFromRoomShouldDelegateToRoomClient() {
    when(matrixRoomClient.banUserFromRoom(MATRIX_ROOM_ID, MATRIX_USER_ID, ACCESS_TOKEN))
        .thenReturn(true);
    var service = matrixSynapseService();

    assertThat(service.banUserFromRoom(MATRIX_ROOM_ID, MATRIX_USER_ID, ACCESS_TOKEN)).isTrue();
    verify(matrixRoomClient).banUserFromRoom(MATRIX_ROOM_ID, MATRIX_USER_ID, ACCESS_TOKEN);
  }

  @Test
  void unbanUserFromRoomShouldDelegateToRoomClient() {
    when(matrixRoomClient.unbanUserFromRoom(MATRIX_ROOM_ID, MATRIX_USER_ID, ACCESS_TOKEN))
        .thenReturn(true);
    var service = matrixSynapseService();

    assertThat(service.unbanUserFromRoom(MATRIX_ROOM_ID, MATRIX_USER_ID, ACCESS_TOKEN)).isTrue();
    verify(matrixRoomClient).unbanUserFromRoom(MATRIX_ROOM_ID, MATRIX_USER_ID, ACCESS_TOKEN);
  }

  @Test
  void banUserFromRoomAsModeratorShouldMintModeratorTokenAndBan() {
    var moderatorId = "@moderator:example.org";
    var expectedUri =
        URI.create(MATRIX_BASE_URL + "/_synapse/admin/v1/users/%40moderator%3Aexample.org/login");
    stubAdminLogin(expectedUri);
    when(matrixRoomClient.banUserFromRoom(MATRIX_ROOM_ID, MATRIX_USER_ID, MATRIX_USER_TOKEN))
        .thenReturn(true);
    var service = matrixSynapseService();

    assertThat(service.banUserFromRoomAsModerator(MATRIX_ROOM_ID, MATRIX_USER_ID, moderatorId))
        .isTrue();
    verify(matrixRoomClient).banUserFromRoom(MATRIX_ROOM_ID, MATRIX_USER_ID, MATRIX_USER_TOKEN);
  }

  @Test
  void banUserFromRoomAsModeratorShouldReturnFalse_WhenModeratorTokenCannotBeMinted() {
    var service = matrixSynapseService();
    // No admin credentials configured -> admin token unavailable -> impersonation fails.
    assertThat(
            service.banUserFromRoomAsModerator(
                MATRIX_ROOM_ID, MATRIX_USER_ID, "@moderator:example.org"))
        .isFalse();
    verifyNoInteractions(matrixRoomClient);
  }

  private void stubAdminLogin(URI expectedLoginAsUserUri) {
    stubAdminPasswordLoginOnly();
    when(restTemplate.postForEntity(
            eq(expectedLoginAsUserUri), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", MATRIX_USER_TOKEN)));
  }

  private void stubAdminPasswordLoginOnly() {
    matrixConfig.setApiUrl(MATRIX_BASE_URL);
    matrixConfig.setAdminUsername("admin");
    matrixConfig.setAdminPassword("admin-password");
    when(restTemplate.postForEntity(
            eq(MATRIX_BASE_URL + "/_matrix/client/r0/login"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", MATRIX_ADMIN_TOKEN)));
  }

  @Test
  void isExpiredShouldTreatEntryAsExpiredOnlyOnceNowReachesExpiry() {
    assertThat(MatrixSynapseService.isExpired(1000L, 999L)).isFalse();
    assertThat(MatrixSynapseService.isExpired(1000L, 1000L)).isTrue();
    assertThat(MatrixSynapseService.isExpired(1000L, 1001L)).isTrue();
  }

  @Test
  void loginUserShouldServeAccessTokenFromCacheWhileWithinTtl() {
    var clock = new AtomicLong(0L);
    var service = matrixSynapseServiceWithClock(clock::get);
    stubPasswordLogin("fresh-token");

    var first = service.loginUser("alice", "secret");
    // Advance well within the 50-minute TTL: still a cache hit, no second HTTP login.
    clock.set(10 * 60 * 1000L);
    var second = service.loginUser("alice", "secret");

    assertThat(first).isEqualTo("fresh-token");
    assertThat(second).isEqualTo("fresh-token");
    verify(restTemplate, times(1))
        .postForEntity(
            eq("https://matrix.example/_matrix/client/r0/login"),
            any(HttpEntity.class),
            eq(Map.class));
  }

  @Test
  void loginUserShouldNotServeExpiredAccessTokenAndFetchesAFreshOne() {
    var clock = new AtomicLong(0L);
    var service = matrixSynapseServiceWithClock(clock::get);
    // First login caches "stale-token"; after TTL elapses, a second login must fetch "fresh-token".
    when(restTemplate.postForEntity(
            eq("https://matrix.example/_matrix/client/r0/login"),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", "stale-token")))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", "fresh-token")));
    matrixConfig.setApiUrl("https://matrix.example");

    var stale = service.loginUser("alice", "secret");
    // Jump just past the 50-minute TTL so the cached entry is expired on the next read.
    clock.set(50 * 60 * 1000L + 1L);
    var fresh = service.loginUser("alice", "secret");

    assertThat(stale).isEqualTo("stale-token");
    assertThat(fresh)
        .as("expired cache entry must not be served; a fresh token is fetched")
        .isEqualTo("fresh-token");
    verify(restTemplate, times(2))
        .postForEntity(
            eq("https://matrix.example/_matrix/client/r0/login"),
            any(HttpEntity.class),
            eq(Map.class));
  }

  private void stubPasswordLogin(String accessToken) {
    matrixConfig.setApiUrl("https://matrix.example");
    when(restTemplate.postForEntity(
            eq("https://matrix.example/_matrix/client/r0/login"),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", accessToken)));
  }

  private MatrixSynapseService matrixSynapseServiceWithClock(LongSupplier nowSupplier) {
    return new MatrixSynapseService(
        matrixConfig,
        restTemplate,
        matrixLongPollRestTemplate,
        matrixRoomClient,
        matrixMediaClient,
        nowSupplier);
  }

  private MatrixSynapseService matrixSynapseService() {
    return new MatrixSynapseService(
        matrixConfig,
        restTemplate,
        matrixLongPollRestTemplate,
        matrixRoomClient,
        matrixMediaClient);
  }

  // -------------------------------------------------------------------------
  // createUser
  // -------------------------------------------------------------------------

  @Test
  void createUser_success_buildsRegistrationRequestWithNonceAndMac() throws Exception {
    // New platform users must be registered in Synapse with a signed nonce/MAC pair.
    stubRegisterEndpoint();
    when(restTemplate.getForEntity(REGISTER_URL, String.class))
        .thenReturn(ResponseEntity.ok("{\"nonce\":\"nonce-abc\"}"));
    var responseBody = new MatrixCreateUserResponseDTO();
    responseBody.setUserId("@newuser:matrix.example.com");
    var createCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    when(restTemplate.postForEntity(
            eq(REGISTER_URL), createCaptor.capture(), eq(MatrixCreateUserResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(responseBody));

    var response = matrixSynapseService().createUser("newuser", "secret", "New User");

    assertThat(response.getBody().getUserId()).isEqualTo("@newuser:matrix.example.com");
    @SuppressWarnings("unchecked")
    var body = (MatrixCreateUserRequestDTO) createCaptor.getValue().getBody();
    assertThat(body.getUsername()).isEqualTo("newuser");
    assertThat(body.getPassword()).isEqualTo("secret");
    assertThat(body.getDisplayName()).isEqualTo("New User");
    assertThat(body.isAdmin()).isFalse();
    assertThat(body.getNonce()).isEqualTo("nonce-abc");
    assertThat(body.getMac()).isNotBlank();
    assertThat(createCaptor.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer " + REGISTRATION_SECRET);
  }

  @Test
  void createUser_missingNonce_throwsMatrixCreateUserException() {
    // Registration cannot proceed without a Synapse-issued nonce.
    stubRegisterEndpoint();
    when(restTemplate.getForEntity(REGISTER_URL, String.class))
        .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}"));

    assertThatThrownBy(() -> matrixSynapseService().createUser("newuser", "secret", "New User"))
        .isInstanceOf(MatrixCreateUserException.class)
        .hasMessage("Could not create user (newuser) in Matrix");
  }

  @Test
  void createUser_httpClientError_throwsMatrixCreateUserException() {
    // Synapse rejection must surface as a typed registration failure for callers.
    stubRegisterEndpoint();
    when(restTemplate.getForEntity(REGISTER_URL, String.class))
        .thenReturn(ResponseEntity.ok("{\"nonce\":\"nonce-abc\"}"));
    when(restTemplate.postForEntity(
            eq(REGISTER_URL), any(HttpEntity.class), eq(MatrixCreateUserResponseDTO.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.CONFLICT,
                "Conflict",
                null,
                "{\"error\":\"user exists\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThatThrownBy(() -> matrixSynapseService().createUser("newuser", "secret", "New User"))
        .isInstanceOf(MatrixCreateUserException.class)
        .hasMessageContaining("Could not create user (newuser) in Matrix");
  }

  @Test
  void createUser_unexpectedError_throwsMatrixCreateUserException() {
    // Network failures during registration must not leak as unchecked exceptions.
    stubRegisterEndpoint();
    when(restTemplate.getForEntity(REGISTER_URL, String.class))
        .thenThrow(new RuntimeException("connection reset"));

    assertThatThrownBy(() -> matrixSynapseService().createUser("newuser", "secret", "New User"))
        .isInstanceOf(MatrixCreateUserException.class)
        .hasMessage("Could not create user (newuser) in Matrix");
  }

  // -------------------------------------------------------------------------
  // getAdminToken / getAdminAccessToken
  // -------------------------------------------------------------------------

  @Test
  void getAdminToken_createsAdminUserWhenInitialLoginFails() {
    // First deployment auto-provisions the technical admin account when login fails.
    stubRegisterEndpoint();
    matrixConfig.setAdminUsername("sysadmin");
    matrixConfig.setAdminPassword("admin-pass");
    when(restTemplate.postForEntity(eq(LOGIN_URL), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", ADMIN_TOKEN)));
    when(restTemplate.getForEntity(REGISTER_URL, String.class))
        .thenReturn(ResponseEntity.ok("{\"nonce\":\"admin-nonce\"}"));
    var adminCreateCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    when(restTemplate.postForEntity(
            eq(REGISTER_URL), adminCreateCaptor.capture(), eq(MatrixCreateUserResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(new MatrixCreateUserResponseDTO()));

    assertThat(matrixSynapseService().getAdminToken()).isEqualTo(ADMIN_TOKEN);

    @SuppressWarnings("unchecked")
    var adminBody = (MatrixCreateUserRequestDTO) adminCreateCaptor.getValue().getBody();
    assertThat(adminBody.isAdmin()).isTrue();
    assertThat(adminBody.getUsername()).isEqualTo("sysadmin");
  }

  @Test
  void getAdminToken_reusesCachedTokenWithinFiftyMinuteWindow() {
    // Repeated admin operations must not re-login on every call within the token TTL.
    var clock = new AtomicLong(0L);
    var service = matrixSynapseServiceWithClock(clock::get);
    matrixConfig.setAdminUsername("admin");
    matrixConfig.setAdminPassword("admin-password");
    when(restTemplate.postForEntity(eq(LOGIN_URL), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", ADMIN_TOKEN)));

    assertThat(service.getAdminToken()).isEqualTo(ADMIN_TOKEN);
    clock.set(30 * 60 * 1000L);
    assertThat(service.getAdminToken()).isEqualTo(ADMIN_TOKEN);

    verify(restTemplate, times(1))
        .postForEntity(eq(LOGIN_URL), any(HttpEntity.class), eq(Map.class));
  }

  @Test
  void getAdminToken_returnsNullWhenCredentialsNotConfigured() {
    // Missing admin credentials must disable privileged Synapse calls instead of failing hard.
    matrixConfig.setAdminUsername(null);
    matrixConfig.setAdminPassword(null);

    assertThat(matrixSynapseService().getAdminToken()).isNull();
    verifyNoInteractions(restTemplate);
  }

  // -------------------------------------------------------------------------
  // loginAsUser / loginAsUserAccessToken
  // -------------------------------------------------------------------------

  @Test
  void loginAsUser_blankMatrixUserId_returnsNullWithoutCallingSynapse() {
    // Impersonation requires a valid Matrix user id; blank ids are rejected early.
    assertThat(matrixSynapseService().loginAsUser("  ", 60_000L)).isNull();
    verifyNoInteractions(restTemplate);
  }

  @Test
  void loginAsUser_validForMs_includesValidityInRequestBody() {
    // Short-lived browser tokens must tell Synapse when the impersonation expires.
    var service = matrixSynapseService();
    var expectedUri =
        URI.create(MATRIX_BASE_URL + "/_synapse/admin/v1/users/%40alice%3Aexample.org/login");
    stubAdminPasswordLoginOnly();
    var bodyCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    long beforeCall = System.currentTimeMillis();

    service.loginAsUser("@alice:example.org", 120_000L);

    verify(restTemplate).postForEntity(eq(expectedUri), bodyCaptor.capture(), eq(Map.class));
    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) bodyCaptor.getValue().getBody();
    assertThat(body).containsKey("valid_until_ms");
    assertThat((Long) body.get("valid_until_ms"))
        .isBetween(beforeCall + 120_000L, beforeCall + 120_000L + 5_000L);
  }

  @Test
  void loginAsUser_httpClientError_returnsNull() {
    // Impersonation failures must degrade gracefully for the messaging UI.
    var expectedUri =
        URI.create(MATRIX_BASE_URL + "/_synapse/admin/v1/users/%40alice%3Aexample.org/login");
    stubAdminPasswordLoginOnly();
    when(restTemplate.postForEntity(eq(expectedUri), any(HttpEntity.class), eq(Map.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThat(matrixSynapseService().loginAsUser("@alice:example.org", 60_000L)).isNull();
  }

  @Test
  void loginAsUserAccessToken_nullResponse_returnsNull() {
    // Callers expect null when Synapse returns no impersonation payload.
    matrixConfig.setAdminUsername(null);
    matrixConfig.setAdminPassword(null);

    assertThat(matrixSynapseService().loginAsUserAccessToken("@alice:example.org")).isNull();
  }

  @Test
  void loginAsUserAccessToken_missingAccessToken_returnsNull() {
    // A login-as-user response without access_token must not be treated as success.
    var expectedUri =
        URI.create(MATRIX_BASE_URL + "/_synapse/admin/v1/users/%40alice%3Aexample.org/login");
    stubAdminPasswordLoginOnly();
    when(restTemplate.postForEntity(eq(expectedUri), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("user_id", "@alice:example.org")));

    assertThat(matrixSynapseService().loginAsUserAccessToken("@alice:example.org")).isNull();
  }

  // -------------------------------------------------------------------------
  // updateUserDisplayName
  // -------------------------------------------------------------------------

  @Test
  void updateUserDisplayName_success_returnsTrue() {
    // Consultant display names shown in Matrix must be updatable via the admin API.
    stubAdminLogin();
    when(restTemplate.exchange(
            org.mockito.ArgumentMatchers.contains("/_synapse/admin/v2/users/"),
            eq(HttpMethod.PUT),
            any(HttpEntity.class),
            eq(String.class)))
        .thenReturn(ResponseEntity.ok(""));

    assertThat(matrixSynapseService().updateUserDisplayName(MATRIX_USER_ID, "Seeker")).isTrue();
  }

  @Test
  void updateUserDisplayName_noAdminToken_returnsFalse() {
    // Display-name updates are skipped when privileged Synapse access is unavailable.
    matrixConfig.setAdminUsername("");

    assertThat(matrixSynapseService().updateUserDisplayName(MATRIX_USER_ID, "Seeker")).isFalse();
    verifyNoInteractions(restTemplate);
  }

  @Test
  void updateUserDisplayName_exception_returnsFalse() {
    // Display-name sync is best-effort and must not break account flows.
    stubAdminLogin();
    when(restTemplate.exchange(
            any(String.class), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class)))
        .thenThrow(new RuntimeException("synapse down"));

    assertThat(matrixSynapseService().updateUserDisplayName(MATRIX_USER_ID, "Seeker")).isFalse();
  }

  // -------------------------------------------------------------------------
  // getRoomMembers
  // -------------------------------------------------------------------------

  @Test
  void getRoomMembers_noAdminToken_returnsEmptyOptional() {
    // Room membership cannot be read without admin credentials.
    matrixConfig.setAdminUsername("");

    assertThat(matrixSynapseService().getRoomMembers(MATRIX_ROOM_ID)).isEmpty();
    verifyNoInteractions(restTemplate);
  }

  @Test
  void getRoomMembers_unexpectedResponseShape_returnsEmptyOptional() {
    // Callers treat empty as "unknown" when Synapse returns an unexpected payload.
    stubAdminLogin();
    when(restTemplate.exchange(
            org.mockito.ArgumentMatchers.contains("/members"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("unexpected", List.of())));

    assertThat(matrixSynapseService().getRoomMembers(MATRIX_ROOM_ID)).isEmpty();
  }

  @Test
  void getRoomMembers_success_returnsMemberList() {
    // Supervision and moderation flows need the authoritative room member list.
    stubAdminLogin();
    when(restTemplate.exchange(
            org.mockito.ArgumentMatchers.contains("/members"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(
            ResponseEntity.ok(
                Map.of(
                    "members", List.of("@alice:matrix.example.com", "@bob:matrix.example.com"))));

    var members = matrixSynapseService().getRoomMembers(MATRIX_ROOM_ID);

    assertThat(members).contains(List.of("@alice:matrix.example.com", "@bob:matrix.example.com"));
  }

  @Test
  void getRoomMembers_exception_returnsEmptyOptional() {
    // Membership lookup is best-effort and must never throw to callers.
    stubAdminLogin();
    when(restTemplate.exchange(
            any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
        .thenThrow(new RuntimeException("synapse down"));

    assertThat(matrixSynapseService().getRoomMembers(MATRIX_ROOM_ID)).isEmpty();
  }

  // -------------------------------------------------------------------------
  // sendMessage
  // -------------------------------------------------------------------------

  @Test
  void sendMessage_success_returnsSynapseResponseBody() {
    // Chat messages must be delivered to the session's Matrix room.
    matrixConfig.setApiUrl(MATRIX_BASE_URL);
    var synapseResponse = Map.<String, Object>of("event_id", "$event123");
    when(restTemplate.exchange(
            org.mockito.ArgumentMatchers.contains("/send/m.room.message/"),
            eq(HttpMethod.PUT),
            any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(synapseResponse));

    var result = matrixSynapseService().sendMessage(MATRIX_ROOM_ID, "hello", ACCESS_TOKEN);

    assertThat(result).isEqualTo(synapseResponse);
  }

  @Test
  void sendMessage_exception_returnsErrorMap() {
    // Send failures must return a structured error instead of propagating exceptions.
    matrixConfig.setApiUrl(MATRIX_BASE_URL);
    when(restTemplate.exchange(
            any(String.class), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Map.class)))
        .thenThrow(new RuntimeException("room not found"));

    var result = matrixSynapseService().sendMessage(MATRIX_ROOM_ID, "hello", ACCESS_TOKEN);

    assertThat(result).containsKey("error");
    assertThat(result.get("error")).asString().contains("room not found");
  }

  // -------------------------------------------------------------------------
  // findOnlineMatrixUserIds
  // -------------------------------------------------------------------------

  @Test
  void findOnlineMatrixUserIds_presenceDisabled_returnsEmptyOptional() {
    // Availability routing falls back when Matrix presence is turned off in config.
    matrixConfig.setPresenceEnabled(false);

    assertThat(matrixSynapseService().findOnlineMatrixUserIds(List.of(MATRIX_USER_ID))).isEmpty();
    verifyNoInteractions(restTemplate);
  }

  @Test
  void findOnlineMatrixUserIds_noAdminToken_returnsEmptyOptional() {
    // Presence cannot be polled without a privileged Synapse admin token.
    matrixConfig.setPresenceEnabled(true);
    matrixConfig.setAdminUsername("");

    assertThat(matrixSynapseService().findOnlineMatrixUserIds(List.of(MATRIX_USER_ID))).isEmpty();
  }

  @Test
  void findOnlineMatrixUserIds_emptyInput_returnsEmptySet() {
    // An empty candidate list is a valid authoritative answer: nobody is online.
    matrixConfig.setPresenceEnabled(true);
    stubAdminLogin();

    var result = matrixSynapseService().findOnlineMatrixUserIds(List.of());

    assertThat(result).contains(Set.of());
  }

  @Test
  void findOnlineMatrixUserIds_mixedPresence_returnsOnlyAvailableUsers() {
    // Live-chat routing must include only consultants with an active Matrix client.
    matrixConfig.setPresenceEnabled(true);
    matrixConfig.setPresenceActiveThresholdMs(300_000L);
    stubAdminLogin();
    var onlineId = "@online:matrix.example.com";
    var offlineId = "@offline:matrix.example.com";
    when(restTemplate.exchange(
            org.mockito.ArgumentMatchers.contains(onlineId),
            eq(HttpMethod.GET),
            any(),
            eq(Map.class)))
        .thenReturn(
            ResponseEntity.ok(
                Map.of("presence", "online", "currently_active", true, "last_active_ago", 0)));
    when(restTemplate.exchange(
            org.mockito.ArgumentMatchers.contains(offlineId),
            eq(HttpMethod.GET),
            any(),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("presence", "offline")));

    var result = matrixSynapseService().findOnlineMatrixUserIds(List.of(onlineId, offlineId));

    assertThat(result).contains(Set.of(onlineId));
  }

  @Test
  void findOnlineMatrixUserIds_allLookupsFail_returnsEmptyOptional() {
    // When every presence lookup fails, callers must fall back to another availability signal.
    matrixConfig.setPresenceEnabled(true);
    stubAdminLogin();
    when(restTemplate.exchange(
            any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
        .thenThrow(new RuntimeException("presence disabled on server"));

    assertThat(
            matrixSynapseService()
                .findOnlineMatrixUserIds(List.of("@a:matrix.example.com", "@b:matrix.example.com")))
        .isEmpty();
  }

  // -------------------------------------------------------------------------
  // loginUserViaAdmin cache
  // -------------------------------------------------------------------------

  @Test
  void loginUserViaAdmin_cacheHitWithinTtl_reusesImpersonationToken() {
    // Repeated moderation actions should reuse the short-lived impersonation token.
    var clock = new AtomicLong(0L);
    var service = matrixSynapseServiceWithClock(clock::get);
    var expectedUri =
        URI.create(MATRIX_BASE_URL + "/_synapse/admin/v1/users/%40alice%3Aexample.org/login");
    stubAdminLogin(expectedUri);

    assertThat(service.loginUserViaAdmin("@alice:example.org")).isEqualTo(MATRIX_USER_TOKEN);
    clock.set(10 * 60 * 1000L);
    assertThat(service.loginUserViaAdmin("@alice:example.org")).isEqualTo(MATRIX_USER_TOKEN);

    verify(restTemplate, times(1))
        .postForEntity(eq(expectedUri), any(HttpEntity.class), eq(Map.class));
  }

  @Test
  void loginUserViaAdmin_cacheExpiredAfterTtl_fetchesFreshToken() {
    // Expired impersonation tokens must be refreshed so Synapse does not reject stale credentials.
    var clock = new AtomicLong(0L);
    var service = matrixSynapseServiceWithClock(clock::get);
    var expectedUri =
        URI.create(MATRIX_BASE_URL + "/_synapse/admin/v1/users/%40alice%3Aexample.org/login");
    stubAdminPasswordLoginOnly();
    when(restTemplate.postForEntity(eq(expectedUri), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", "token-v1")))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", "token-v2")));

    assertThat(service.loginUserViaAdmin("@alice:example.org")).isEqualTo("token-v1");
    clock.set(50 * 60 * 1000L + 1L);
    assertThat(service.loginUserViaAdmin("@alice:example.org")).isEqualTo("token-v2");

    verify(restTemplate, times(2))
        .postForEntity(eq(expectedUri), any(HttpEntity.class), eq(Map.class));
  }

  @Test
  void loginUserViaAdmin_noAccessTokenInResponse_returnsNull() {
    // Impersonation without an access_token must be treated as failure.
    var expectedUri =
        URI.create(MATRIX_BASE_URL + "/_synapse/admin/v1/users/%40alice%3Aexample.org/login");
    stubAdminPasswordLoginOnly();
    when(restTemplate.postForEntity(eq(expectedUri), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("user_id", "@alice:example.org")));

    assertThat(matrixSynapseService().loginUserViaAdmin("@alice:example.org")).isNull();
  }

  private void stubRegisterEndpoint() {
    // apiUrl and registration secret are configured in setUpMatrixConfig()
  }
}
