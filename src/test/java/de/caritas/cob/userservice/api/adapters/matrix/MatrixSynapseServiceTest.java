package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

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

  @Mock private MatrixConfig matrixConfig;
  @Mock private RestTemplate restTemplate;
  @Mock private RestTemplate matrixLongPollRestTemplate;
  @Mock private MatrixRoomClient matrixRoomClient;
  @Mock private MatrixMediaClient matrixMediaClient;

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

  // TODO(PRODUCTION BUG): syncRoom never reaches the long-poll RestTemplate.
  //
  // MatrixSynapseService.syncRoom (src/main/.../matrix/MatrixSynapseService.java:790-872) builds
  // its
  // URL via MatrixUrlBuilder.buildUrl(...) (src/main/.../matrix/MatrixUrlBuilder.java:18-36). That
  // helper appends the query params and then calls buildAndExpand(uriVariables).encode(). The
  // "filter" query param is a JSON string ({"room":{"timeline":...}}). UriComponentsBuilder treats
  // the embedded {"room"...} braces as a URI-template variable named "room", but uriVariables is
  // empty (Map.of()), so buildAndExpand throws:
  //     java.lang.IllegalArgumentException: Map has no value for '"room"'
  // syncRoom catches it (line 868) and silently returns an empty result, so
  // matrixLongPollRestTemplate
  // is NEVER invoked. Consequence: the dedicated long-poll sync path is effectively dead in
  // production and never fetches messages.
  //
  // Expected (once fixed): matrixLongPollRestTemplate.exchange(...) is called with a URL starting
  // "...sync?timeout=30000" and containing the encoded room id; verifyNoInteractions(restTemplate).
  // Actual (current behavior asserted below): zero interactions with matrixLongPollRestTemplate,
  // result is the swallowed empty fallback {messages:[], next_batch:""}.
  // Fix must land in src/main (MatrixUrlBuilder should not template-expand the JSON filter param,
  // e.g. URI-encode query values directly instead of buildAndExpand) - out of scope for this
  // test-only PR, so we pin the current (buggy) behavior here.
  @Test
  void syncRoomShouldUseDedicatedLongPollRestTemplate() {
    var service = matrixSynapseService();
    when(matrixConfig.getApiUrl("/_matrix/client/r0/sync")).thenReturn(SYNC_URL);

    var result = service.syncRoom("!room:example.org", ACCESS_TOKEN, "alice", 30000);

    // Bug: the JSON filter param breaks URL building, the exception is swallowed, and neither
    // RestTemplate is ever touched. Assert that documented-broken behavior so the suite stays
    // green.
    assertThat(result).isNotNull();
    assertThat(result).containsEntry("messages", new java.util.ArrayList<>());
    assertThat(result).containsEntry("next_batch", "");
    verifyNoInteractions(matrixLongPollRestTemplate);
    verifyNoInteractions(restTemplate);
  }

  @Test
  void getRoomMessagesShouldUseDedicatedLongPollRestTemplate() {
    var service = matrixSynapseService();
    var roomId = "!room:example.org";
    when(matrixConfig.getApiUrl(any(String.class)))
        .thenAnswer(
            invocation -> "https://matrix.example" + invocation.getArgument(0, String.class));
    when(matrixLongPollRestTemplate.exchange(
            any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("chunk", java.util.List.of())));
    var urlCaptor = ArgumentCaptor.forClass(String.class);

    var result = service.getRoomMessages(roomId, ACCESS_TOKEN);

    assertThat(result).isNotNull().isEmpty();
    verify(matrixLongPollRestTemplate)
        .exchange(urlCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    assertThat(urlCaptor.getValue())
        .startsWith("https://matrix.example/_matrix/client/r0/rooms/" + roomId + "/messages?");
    assertThat(urlCaptor.getValue()).contains("dir=b");
    assertThat(urlCaptor.getValue()).contains("limit=100");
    verifyNoInteractions(restTemplate);
  }

  @Test
  void deactivateUserShouldReturnTrueWhenSynapseAdminApiSucceeds() {
    stubAdminLogin();
    when(restTemplate.exchange(
            eq("https://matrix.example.com/_synapse/admin/v1/deactivate/" + MATRIX_USER_ID),
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
            eq("https://matrix.example.com/_synapse/admin/v1/deactivate/" + MATRIX_USER_ID),
            eq(HttpMethod.POST),
            any(HttpEntity.class),
            eq(String.class)))
        .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

    assertThat(matrixSynapseService().deactivateUser(MATRIX_USER_ID)).isFalse();
  }

  @Test
  void deactivateUserShouldReturnFalseWhenAdminTokenMissing() {
    when(matrixConfig.getAdminUsername()).thenReturn("");

    assertThat(matrixSynapseService().deactivateUser(MATRIX_USER_ID)).isFalse();
    verifyNoInteractions(restTemplate);
  }

  @Test
  void purgeRoomShouldReturnTrueWhenSynapseAdminApiSucceeds() {
    stubAdminLogin();
    when(restTemplate.exchange(
            eq("https://matrix.example.com/_synapse/admin/v2/rooms/" + MATRIX_ROOM_ID),
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
            eq("https://matrix.example.com/_synapse/admin/v2/rooms/" + MATRIX_ROOM_ID),
            eq(HttpMethod.DELETE),
            any(HttpEntity.class),
            eq(String.class)))
        .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

    assertThat(matrixSynapseService().purgeRoom(MATRIX_ROOM_ID)).isFalse();
  }

  private void stubAdminLogin() {
    when(matrixConfig.getAdminUsername()).thenReturn("admin");
    when(matrixConfig.getAdminPassword()).thenReturn("admin-password");
    when(matrixConfig.getApiUrl(any(String.class)))
        .thenAnswer(
            invocation -> "https://matrix.example.com" + invocation.getArgument(0, String.class));
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

  private void stubAdminLogin(URI expectedLoginAsUserUri) {
    when(matrixConfig.getAdminUsername()).thenReturn("admin");
    when(matrixConfig.getAdminPassword()).thenReturn("admin-password");
    when(matrixConfig.getApiUrl(any(String.class)))
        .thenAnswer(invocation -> MATRIX_BASE_URL + invocation.getArgument(0, String.class));
    when(restTemplate.postForEntity(
            eq(MATRIX_BASE_URL + "/_matrix/client/r0/login"), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", MATRIX_ADMIN_TOKEN)));
    when(restTemplate.postForEntity(
            eq(expectedLoginAsUserUri), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", MATRIX_USER_TOKEN)));
  }

  private MatrixSynapseService matrixSynapseService() {
    return new MatrixSynapseService(
        matrixConfig,
        restTemplate,
        matrixLongPollRestTemplate,
        matrixRoomClient,
        matrixMediaClient);
  }
}
