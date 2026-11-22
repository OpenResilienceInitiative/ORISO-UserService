package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserResponseDTO;
import de.caritas.cob.userservice.api.config.observability.LiveChatDiagnosticMetrics;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class MatrixRoomClientTest {

  private static final String ACCESS_TOKEN = "access-token";
  private static final String API_URL = "https://matrix.example";
  private static final String ROOM_ID = "!room:matrix.example";
  private static final String USER_ID = "@user:matrix.example";
  // encode().buildAndExpand() encodes Matrix IDs as opaque data: '!' → %21, ':' → %3A, '@' → %40.
  // UriUtils.encodePathSegment leaves those RFC-3986 sub-delimiters unencoded — do NOT use it
  // here, or the mock URLs will not match what MatrixUrlBuilder actually produces.
  private static final String ENCODED_ROOM_ID = "%21room%3Amatrix.example";
  private static final String ENCODED_USER_ID = "%40user%3Amatrix.example";

  @Mock private MatrixConfig matrixConfig;
  @Mock private RestTemplate restTemplate;
  @Mock private LiveChatDiagnosticMetrics diagnosticMetrics;

  @Captor private ArgumentCaptor<HttpEntity<MatrixCreateRoomRequestDTO>> createRoomRequestCaptor;
  @Captor private ArgumentCaptor<HttpEntity<MatrixInviteUserRequestDTO>> inviteRequestCaptor;
  @Captor private ArgumentCaptor<HttpEntity<Map<String, Object>>> mapRequestCaptor;

  @InjectMocks private MatrixRoomClient matrixRoomClient;

  private static URI uri(String value) {
    return URI.create(value);
  }

  @BeforeEach
  void setup() {
    when(matrixConfig.getApiUrl(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> API_URL + invocation.getArgument(0, String.class));
  }

  /** A RestTemplate message can carry the request URL, and with it the Matrix user ID. */
  @Test
  void guestCleanupFailuresAreLoggedWithoutTheRestTemplateMessage() {
    var failure = new org.springframework.web.client.ResourceAccessException("GET " + USER_ID);
    when(restTemplate.postForEntity(
            org.mockito.ArgumentMatchers.any(URI.class),
            org.mockito.ArgumentMatchers.any(),
            eq(Map.class)))
        .thenThrow(failure);
    when(restTemplate.exchange(
            org.mockito.ArgumentMatchers.any(URI.class),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(),
            eq(Map.class)))
        .thenThrow(failure);

    try (var logs =
        de.caritas.cob.userservice.testutils.LogbackCaptor.forClass(MatrixRoomClient.class)) {
      assertThat(matrixRoomClient.kickUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN)).isFalse();
      assertThat(matrixRoomClient.getRoomMembership(ROOM_ID, USER_ID, ACCESS_TOKEN)).isEmpty();
      assertThat(matrixRoomClient.clearUserPowerLevel(ROOM_ID, USER_ID, ACCESS_TOKEN)).isFalse();
      assertThat(logs.messages(ch.qos.logback.classic.Level.WARN))
          .hasSize(3)
          .allSatisfy(message -> assertThat(message).doesNotContain(USER_ID));
    }
  }

  @Test
  void createRoom_ShouldReportARateLimitWithTheServersDelay() {
    // US#1737: the holding room of a new enquiry must be retried, not dropped, on a 429.
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/createRoom")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixCreateRoomResponseDTO.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                null,
                "{\"errcode\":\"M_LIMIT_EXCEEDED\",\"retry_after_ms\":3256}"
                    .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThatThrownBy(() -> matrixRoomClient.createRoom("Room name", "room-alias", ACCESS_TOKEN))
        .isInstanceOfSatisfying(
            de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomRateLimitedException
                .class,
            ex -> assertThat(ex.getRetryAfterMs()).isEqualTo(3256L));
  }

  @Test
  void createRoom_ShouldSendPrivateRoomCreationRequest() throws Exception {
    var responseBody = new MatrixCreateRoomResponseDTO();
    responseBody.setRoomId(ROOM_ID);
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/createRoom")),
            createRoomRequestCaptor.capture(),
            eq(MatrixCreateRoomResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(responseBody));
    stubSynapseDefaultPowerLevels();

    var response = matrixRoomClient.createRoom("Room name", "room-alias", ACCESS_TOKEN);

    assertThat(response.getBody()).isSameAs(responseBody);
    var request = createRoomRequestCaptor.getValue();
    assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer " + ACCESS_TOKEN);
    assertThat(request.getBody().getName()).isEqualTo("Room name");
    assertThat(request.getBody().getRoomAliasName()).isEqualTo("room-alias");
    assertThat(request.getBody().getPreset()).isEqualTo("private_chat");
    assertThat(request.getBody().getVisibility()).isEqualTo("private");
    assertThat(request.getBody().getInitialState()).isEmpty();
    verify(diagnosticMetrics).recordRoomCreation(false, LiveChatDiagnosticMetrics.Outcome.SUCCESS);
  }

  @Test
  void createRoom_WhenEncryptionEnabled_ShouldSendMegolmInitialState() throws Exception {
    var responseBody = new MatrixCreateRoomResponseDTO();
    responseBody.setRoomId(ROOM_ID);
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/createRoom")),
            createRoomRequestCaptor.capture(),
            eq(MatrixCreateRoomResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(responseBody));
    stubSynapseDefaultPowerLevels();

    matrixRoomClient.createRoom("Room name", "room-alias", ACCESS_TOKEN, true);

    var initialState = createRoomRequestCaptor.getValue().getBody().getInitialState();
    assertThat(initialState).hasSize(1);
    var event = initialState.getFirst();
    assertThat(event.getType()).isEqualTo("m.room.encryption");
    assertThat(event.getStateKey()).isEmpty();
    assertThat(event.getContent()).isEqualTo(Map.of("algorithm", "m.megolm.v1.aes-sha2"));
    verify(diagnosticMetrics).recordRoomCreation(true, LiveChatDiagnosticMetrics.Outcome.SUCCESS);
  }

  @Test
  void createRoom_ForAMembersOnlyRoom_ShouldLockInvitesToTheAdminPowerLevel() throws Exception {
    var responseBody = new MatrixCreateRoomResponseDTO();
    responseBody.setRoomId(ROOM_ID);
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/createRoom")),
            createRoomRequestCaptor.capture(),
            eq(MatrixCreateRoomResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(responseBody));
    stubSynapseDefaultPowerLevels();

    matrixRoomClient.createMembersOnlyRoom("Room name", "room-alias", ACCESS_TOKEN, true);

    var body = createRoomRequestCaptor.getValue().getBody();
    assertThat(body.getPowerLevelContentOverride()).isEqualTo(Map.of("invite", 100));
    assertThat(body.getInitialState())
        .extracting(MatrixCreateRoomRequestDTO.InitialStateEvent::getType)
        .containsExactlyInAnyOrder("m.room.encryption", "m.room.history_visibility");
    assertThat(body.getInitialState())
        .filteredOn(event -> "m.room.history_visibility".equals(event.getType()))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getStateKey()).isEmpty();
              assertThat(event.getContent()).isEqualTo(Map.of("history_visibility", "joined"));
            });
  }

  @Test
  void createRoom_ForAnOrdinaryRoom_ShouldKeepTheDefaultPowerLevels() throws Exception {
    var responseBody = new MatrixCreateRoomResponseDTO();
    responseBody.setRoomId(ROOM_ID);
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/createRoom")),
            createRoomRequestCaptor.capture(),
            eq(MatrixCreateRoomResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(responseBody));
    stubSynapseDefaultPowerLevels();

    matrixRoomClient.createRoom("Room name", "room-alias", ACCESS_TOKEN, true);

    assertThat(createRoomRequestCaptor.getValue().getBody().getPowerLevelContentOverride())
        .isNull();
  }

  /** Synapse's default power levels for a private_chat room (synapse/handlers/room.py). */
  private static Map<String, Object> synapseDefaultPowerLevels() {
    var events = new HashMap<String, Object>();
    events.put("m.room.name", 50);
    events.put("m.room.power_levels", 100);
    events.put("m.room.history_visibility", 100);
    events.put("m.room.canonical_alias", 50);
    events.put("m.room.avatar", 50);
    events.put("m.room.tombstone", 100);
    events.put("m.room.server_acl", 100);
    events.put("m.room.encryption", 100);
    var content = new HashMap<String, Object>();
    content.put("users", Map.of(USER_ID, 100));
    content.put("users_default", 0);
    content.put("events", events);
    content.put("events_default", 0);
    content.put("state_default", 50);
    content.put("ban", 50);
    content.put("kick", 50);
    content.put("redact", 50);
    content.put("invite", 0);
    return content;
  }

  private void stubPowerLevels(Map<String, Object> content) {
    when(restTemplate.exchange(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(content));
  }

  private void stubSynapseDefaultPowerLevels() {
    stubPowerLevels(synapseDefaultPowerLevels());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> writtenPowerLevels() {
    verify(restTemplate)
        .put(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            mapRequestCaptor.capture());
    return mapRequestCaptor.getValue().getBody();
  }

  private void stubRoomCreated() {
    var responseBody = new MatrixCreateRoomResponseDTO();
    responseBody.setRoomId(ROOM_ID);
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/createRoom")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixCreateRoomResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(responseBody));
  }

  /**
   * ORISO-Frontend#2229: only a room moderator may blur a passage. The rule is added to the room's
   * own power levels, so Synapse's defaults (power levels, encryption, ...) stay as they were.
   */
  @Test
  void createRoom_ShouldRequireTheModeratorLevelForPassageBlurs() throws Exception {
    stubRoomCreated();
    stubSynapseDefaultPowerLevels();

    matrixRoomClient.createRoom("Room name", "room-alias", ACCESS_TOKEN, true);

    var written = writtenPowerLevels();
    var expectedEvents = new HashMap<>(objectMapOf(synapseDefaultPowerLevels().get("events")));
    expectedEvents.put("org.oriso.passage_blur", 50);
    assertThat(written.get("events")).isEqualTo(expectedEvents);
    assertThat(written).containsEntry("redact", 50).containsEntry("events_default", 0);
    assertThat(written.get("users")).isEqualTo(Map.of(USER_ID, 100));
    assertThat(mapRequestCaptor.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer " + ACCESS_TOKEN);
  }

  @Test
  void createMembersOnlyRoom_ShouldRequireTheModeratorLevelForPassageBlurs() throws Exception {
    stubRoomCreated();
    stubSynapseDefaultPowerLevels();

    matrixRoomClient.createMembersOnlyRoom("Room name", "room-alias", ACCESS_TOKEN, true);

    assertThat(objectMapOf(writtenPowerLevels().get("events")))
        .containsEntry("org.oriso.passage_blur", 50)
        .containsEntry("m.room.power_levels", 100);
  }

  /** A room without the rule would let an advice seeker blur: creating it counts as failed. */
  @Test
  void createRoom_ShouldFail_WhenThePassageBlurRuleCannotBeWritten() {
    stubRoomCreated();
    stubSynapseDefaultPowerLevels();
    doThrow(new org.springframework.web.client.ResourceAccessException("timeout"))
        .when(restTemplate)
        .put(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class));

    assertThatThrownBy(
            () -> matrixRoomClient.createRoom("Room name", "room-alias", ACCESS_TOKEN, true))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException.class);
    verify(diagnosticMetrics).recordRoomCreation(true, LiveChatDiagnosticMetrics.Outcome.FAILURE);
  }

  @Test
  void requireModeratorForPassageBlur_ShouldNotWrite_WhenTheRoomAlreadyHasTheRule() {
    var content = synapseDefaultPowerLevels();
    var events = new HashMap<>(objectMapOf(content.get("events")));
    events.put("org.oriso.passage_blur", 50);
    content.put("events", events);
    stubPowerLevels(content);

    assertThat(matrixRoomClient.requireModeratorForPassageBlur(ROOM_ID, ACCESS_TOKEN)).isTrue();

    verify(restTemplate, times(0))
        .put(org.mockito.ArgumentMatchers.any(URI.class), org.mockito.ArgumentMatchers.any());
  }

  /** A closed room keeps blurring as locked as every other message: the rule never lowers it. */
  @Test
  void requireModeratorForPassageBlur_ShouldKeepAClosedRoomClosed() {
    var content = synapseDefaultPowerLevels();
    content.put("events_default", 101);
    stubPowerLevels(content);

    assertThat(matrixRoomClient.requireModeratorForPassageBlur(ROOM_ID, ACCESS_TOKEN)).isTrue();

    assertThat(objectMapOf(writtenPowerLevels().get("events")))
        .containsEntry("org.oriso.passage_blur", 101);
  }

  /** With a lower redact level an advice seeker could lift a moderator's blur. */
  @Test
  void requireModeratorForPassageBlur_ShouldRaiseALowerRedactLevelToTheModeratorLevel() {
    var content = synapseDefaultPowerLevels();
    content.put("redact", 0);
    stubPowerLevels(content);

    assertThat(matrixRoomClient.requireModeratorForPassageBlur(ROOM_ID, ACCESS_TOKEN)).isTrue();

    assertThat(writtenPowerLevels()).containsEntry("redact", 50);
  }

  /** With a higher redact level a moderator could not lift another moderator's blur. */
  @Test
  void requireModeratorForPassageBlur_ShouldLowerAHigherRedactLevelToTheModeratorLevel() {
    var content = synapseDefaultPowerLevels();
    content.put("redact", 100);
    stubPowerLevels(content);

    assertThat(matrixRoomClient.requireModeratorForPassageBlur(ROOM_ID, ACCESS_TOKEN)).isTrue();

    assertThat(writtenPowerLevels()).containsEntry("redact", 50);
  }

  @Test
  void requireModeratorForPassageBlur_ShouldReportFalse_WhenThePowerLevelsCannotBeRead() {
    when(restTemplate.exchange(
            org.mockito.ArgumentMatchers.any(URI.class),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

    assertThat(matrixRoomClient.requireModeratorForPassageBlur(ROOM_ID, ACCESS_TOKEN)).isFalse();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMapOf(Object value) {
    return (Map<String, Object>) value;
  }

  // ── ORISO-Frontend#2229: named group moderators hold the moderator level ──

  private static final String HOLDER_ID = USER_ID;
  private static final String CO_MODERATOR_ID = "@comoderator:matrix.example";
  private static final String OTHER_ID = "@other:matrix.example";

  private static Map<String, Object> powerLevelsWithUsers(Map<String, Object> users) {
    var content = synapseDefaultPowerLevels();
    content.put("users", new HashMap<>(users));
    return content;
  }

  private void verifyNoPowerLevelWrite() {
    verify(restTemplate, org.mockito.Mockito.never())
        .put(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class));
  }

  @Test
  void syncModeratorPower_ShouldRaiseANamedModeratorToTheModeratorLevel() {
    stubPowerLevels(powerLevelsWithUsers(Map.of(HOLDER_ID, 100, OTHER_ID, 10)));

    var result =
        matrixRoomClient.syncModeratorPower(
            ROOM_ID, Map.of(CO_MODERATOR_ID, true), HOLDER_ID, ACCESS_TOKEN);

    assertThat(result).isEqualTo(MatrixRoomClient.ModeratorPowerSync.WRITTEN);
    var written = writtenPowerLevels();
    assertThat(written.get("users"))
        .isEqualTo(Map.of(HOLDER_ID, 100, OTHER_ID, 10, CO_MODERATOR_ID, 50));
    assertThat(written).containsEntry("redact", 50).containsEntry("state_default", 50);
    assertThat(mapRequestCaptor.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer " + ACCESS_TOKEN);
  }

  @Test
  void syncModeratorPower_ShouldWriteNothing_WhenTheModeratorAlreadyHoldsTheLevelOrMore() {
    stubPowerLevels(powerLevelsWithUsers(Map.of(HOLDER_ID, 100, CO_MODERATOR_ID, 75)));

    var result =
        matrixRoomClient.syncModeratorPower(
            ROOM_ID, Map.of(CO_MODERATOR_ID, true, HOLDER_ID, true), HOLDER_ID, ACCESS_TOKEN);

    assertThat(result).isEqualTo(MatrixRoomClient.ModeratorPowerSync.UNCHANGED);
    verifyNoPowerLevelWrite();
  }

  @Test
  void syncModeratorPower_ShouldTakeTheModeratorLevelBack_OnlyFromSomeoneWhoHoldsExactlyIt() {
    stubPowerLevels(
        powerLevelsWithUsers(Map.of(HOLDER_ID, 100, CO_MODERATOR_ID, 50, OTHER_ID, 75)));

    var result =
        matrixRoomClient.syncModeratorPower(
            ROOM_ID, Map.of(CO_MODERATOR_ID, false, OTHER_ID, false), HOLDER_ID, ACCESS_TOKEN);

    assertThat(result).isEqualTo(MatrixRoomClient.ModeratorPowerSync.WRITTEN);
    assertThat(writtenPowerLevels().get("users"))
        .isEqualTo(Map.of(HOLDER_ID, 100, CO_MODERATOR_ID, 0, OTHER_ID, 75));
  }

  @Test
  void syncModeratorPower_ShouldNeverLowerTheRoomCreator() {
    stubPowerLevels(powerLevelsWithUsers(Map.of(HOLDER_ID, 100)));

    var result =
        matrixRoomClient.syncModeratorPower(
            ROOM_ID, Map.of(HOLDER_ID, false), HOLDER_ID, ACCESS_TOKEN);

    assertThat(result).isEqualTo(MatrixRoomClient.ModeratorPowerSync.UNCHANGED);
    verifyNoPowerLevelWrite();
  }

  /** Synapse refuses a power-level change from below 100 (the m.room.power_levels level). */
  @Test
  void syncModeratorPower_ShouldNotWrite_WhenTheHolderMayNotChangePowerLevels() {
    stubPowerLevels(powerLevelsWithUsers(Map.of(OTHER_ID, 100, HOLDER_ID, 50)));

    var result =
        matrixRoomClient.syncModeratorPower(
            ROOM_ID, Map.of(CO_MODERATOR_ID, true), HOLDER_ID, ACCESS_TOKEN);

    assertThat(result).isEqualTo(MatrixRoomClient.ModeratorPowerSync.NOT_PERMITTED);
    verifyNoPowerLevelWrite();
  }

  @Test
  void syncModeratorPower_ShouldReportFailure_WhenThePowerLevelsCannotBeRead() {
    when(restTemplate.exchange(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

    var result =
        matrixRoomClient.syncModeratorPower(
            ROOM_ID, Map.of(CO_MODERATOR_ID, true), HOLDER_ID, ACCESS_TOKEN);

    assertThat(result).isEqualTo(MatrixRoomClient.ModeratorPowerSync.FAILED);
  }

  @Test
  void createRoom_ShouldThrowMatrixCreateRoomException_WhenMatrixRejectsRequest() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/createRoom")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixCreateRoomResponseDTO.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                null,
                "{\"error\":\"bad room\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThatThrownBy(() -> matrixRoomClient.createRoom("Room name", "room-alias", ACCESS_TOKEN))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException.class)
        .hasMessageContaining("Could not create room (Room name) in Matrix");
    verify(diagnosticMetrics).recordRoomCreation(false, LiveChatDiagnosticMetrics.Outcome.FAILURE);
  }

  @Test
  void createRoom_ShouldThrowMatrixCreateRoomException_WhenUnexpectedErrorOccurs() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/createRoom")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixCreateRoomResponseDTO.class)))
        .thenThrow(new RuntimeException("connection failed"));

    assertThatThrownBy(() -> matrixRoomClient.createRoom("Room name", "room-alias", ACCESS_TOKEN))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException.class)
        .hasMessage("Could not create room (Room name) in Matrix");
    verify(diagnosticMetrics).recordRoomCreation(false, LiveChatDiagnosticMetrics.Outcome.FAILURE);
  }

  @Test
  void createRoom_ShouldRejectSuccessfulResponseWithoutRoomId() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/createRoom")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixCreateRoomResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(new MatrixCreateRoomResponseDTO()));

    assertThatThrownBy(
            () -> matrixRoomClient.createRoom("Room name", "room-alias", ACCESS_TOKEN, true))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException.class)
        .hasMessage("Could not create room (Room name) in Matrix");
    verify(diagnosticMetrics).recordRoomCreation(true, LiveChatDiagnosticMetrics.Outcome.FAILURE);
  }

  @Test
  void inviteUserToRoom_ShouldSendInviteRequest() throws Exception {
    var responseBody = new MatrixInviteUserResponseDTO();
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/invite")),
            inviteRequestCaptor.capture(),
            eq(MatrixInviteUserResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(responseBody));

    var response = matrixRoomClient.inviteUserToRoom(ROOM_ID, USER_ID, ACCESS_TOKEN);

    assertThat(response.getBody()).isSameAs(responseBody);
    var request = inviteRequestCaptor.getValue();
    assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer " + ACCESS_TOKEN);
    assertThat(request.getBody().getUserId()).isEqualTo(USER_ID);
  }

  @Test
  void inviteUserToRoom_ShouldThrowMatrixInviteUserException_WhenMatrixRejectsRequest() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/invite")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixInviteUserResponseDTO.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{\"error\":\"not allowed\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThatThrownBy(() -> matrixRoomClient.inviteUserToRoom(ROOM_ID, USER_ID, ACCESS_TOKEN))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException.class)
        .hasMessageContaining("Could not invite user");
  }

  @Test
  void inviteUserToRoom_ShouldCapAServerChosenRetryDelay() throws Exception {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/invite")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixInviteUserResponseDTO.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                null,
                "{\"errcode\":\"M_LIMIT_EXCEEDED\",\"retry_after_ms\":600000}"
                    .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8))
        .thenReturn(ResponseEntity.ok(new MatrixInviteUserResponseDTO()));

    long started = System.nanoTime();
    matrixRoomClient.inviteUserToRoom(ROOM_ID, USER_ID, ACCESS_TOKEN);
    long waitedMs = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertThat(waitedMs)
        .isGreaterThanOrEqualTo(MatrixRoomClient.MAX_INVITE_RETRY_DELAY_MS)
        .isLessThan(MatrixRoomClient.MAX_INVITE_RETRY_DELAY_MS + 3000);
  }

  @Test
  void inviteUserToRoom_ShouldRetry_WhenMatrixRateLimitsInvite() throws Exception {
    var responseBody = new MatrixInviteUserResponseDTO();
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/invite")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixInviteUserResponseDTO.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                null,
                "{\"errcode\":\"M_LIMIT_EXCEEDED\",\"retry_after_ms\":1}"
                    .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8))
        .thenReturn(ResponseEntity.ok(responseBody));

    var response = matrixRoomClient.inviteUserToRoom(ROOM_ID, USER_ID, ACCESS_TOKEN);

    assertThat(response.getBody()).isSameAs(responseBody);
    verify(restTemplate, times(2))
        .postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/invite")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixInviteUserResponseDTO.class));
  }

  private static HttpClientErrorException rateLimited(long retryAfterMs) {
    return HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS,
        "Too Many Requests",
        null,
        ("{\"errcode\":\"M_LIMIT_EXCEEDED\",\"retry_after_ms\":" + retryAfterMs + "}")
            .getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }

  @Test
  void inviteUserToRoom_ShouldReportTheUncappedRateLimit_WhenEveryAttemptIsRateLimited() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/invite")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixInviteUserResponseDTO.class)))
        .thenThrow(rateLimited(1), rateLimited(1), rateLimited(600000));

    // ORISO-Frontend#2386: the caller must be able to tell "try again later" from "refused", so
    // it can hand the invite to the background instead of dropping what depends on it. The delay
    // is the server's own value of the last answer, not the capped in-request wait.
    assertThatThrownBy(() -> matrixRoomClient.inviteUserToRoom(ROOM_ID, USER_ID, ACCESS_TOKEN))
        .isInstanceOfSatisfying(
            de.caritas.cob.userservice.api.exception.matrix.MatrixInviteRateLimitedException.class,
            ex -> assertThat(ex.getRetryAfterMs()).isEqualTo(600000L));
    verify(restTemplate, times(3))
        .postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/invite")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixInviteUserResponseDTO.class));
  }

  @Test
  void inviteObserverQuietly_ShouldTellARateLimitApartFromARefusal() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/invite")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(java.util.Map.class)))
        .thenThrow(rateLimited(600000))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"@u is already in the room.\"}"
                    .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"not allowed\"}"
                    .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThat(matrixRoomClient.inviteObserverQuietly(ROOM_ID, USER_ID, ACCESS_TOKEN))
        .isEqualTo(MatrixRoomClient.QuietInvite.RATE_LIMITED);
    assertThat(matrixRoomClient.inviteObserverQuietly(ROOM_ID, USER_ID, ACCESS_TOKEN))
        .isEqualTo(MatrixRoomClient.QuietInvite.INVITED);
    assertThat(matrixRoomClient.inviteObserverQuietly(ROOM_ID, USER_ID, ACCESS_TOKEN))
        .isEqualTo(MatrixRoomClient.QuietInvite.REFUSED);
  }

  @Test
  void inviteUserToRoomOnce_ShouldReportUncappedServerDelayWithoutRetrying() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/invite")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixInviteUserResponseDTO.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                null,
                "{\"errcode\":\"M_LIMIT_EXCEEDED\",\"retry_after_ms\":600000}"
                    .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    long started = System.nanoTime();
    assertThatThrownBy(() -> matrixRoomClient.inviteUserToRoomOnce(ROOM_ID, USER_ID, ACCESS_TOKEN))
        .isInstanceOfSatisfying(
            de.caritas.cob.userservice.api.exception.matrix.MatrixInviteRateLimitedException.class,
            ex -> assertThat(ex.getRetryAfterMs()).isEqualTo(600000L));
    long waitedMs = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();

    // The request thread must not sleep: the delay is handed back, not waited for.
    assertThat(waitedMs).isLessThan(MatrixRoomClient.MAX_INVITE_RETRY_DELAY_MS);
    verify(restTemplate, times(1))
        .postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/invite")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixInviteUserResponseDTO.class));
  }

  @Test
  void inviteUserToRoomOnce_ShouldThrowPlainInviteException_WhenForbidden() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/invite")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixInviteUserResponseDTO.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{\"errcode\":\"M_FORBIDDEN\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThatThrownBy(() -> matrixRoomClient.inviteUserToRoomOnce(ROOM_ID, USER_ID, ACCESS_TOKEN))
        .isExactlyInstanceOf(
            de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException.class);
  }

  @Test
  void inviteUserToRoom_ShouldThrowMatrixInviteUserException_WhenUnexpectedErrorOccurs() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/invite")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(MatrixInviteUserResponseDTO.class)))
        .thenThrow(new RuntimeException("connection failed"));

    assertThatThrownBy(() -> matrixRoomClient.inviteUserToRoom(ROOM_ID, USER_ID, ACCESS_TOKEN))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException.class)
        .hasMessage("Could not invite user (" + USER_ID + ") to room (" + ROOM_ID + ") in Matrix");
  }

  @Test
  void joinRoom_ShouldReturnTrue_WhenMatrixJoinSucceeds() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/join")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()));

    assertThat(matrixRoomClient.joinRoom(ROOM_ID, ACCESS_TOKEN)).isTrue();
  }

  @Test
  void joinRoom_ShouldReturnTrue_WhenMatrixReportsUserAlreadyJoined() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/join")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{\"error\":\"already in the room\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThat(matrixRoomClient.joinRoom(ROOM_ID, ACCESS_TOKEN)).isTrue();
  }

  @Test
  void joinRoom_ShouldReturnFalse_WhenMatrixRejectsJoinForOtherReason() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/join")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{\"error\":\"not invited\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThat(matrixRoomClient.joinRoom(ROOM_ID, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void joinRoom_ShouldReturnFalse_WhenMatrixReturnsNonSuccessResponse() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/join")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of()));

    assertThat(matrixRoomClient.joinRoom(ROOM_ID, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void setUserPowerLevel_ShouldPreserveExistingUsersAndUpdateTargetUser() {
    var currentUsers = new HashMap<String, Integer>();
    currentUsers.put("@other:matrix.example", 100);
    var currentPowerLevels = new HashMap<String, Object>();
    currentPowerLevels.put("users", currentUsers);
    when(restTemplate.exchange(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(currentPowerLevels));

    var result = matrixRoomClient.setUserPowerLevel(ROOM_ID, USER_ID, 50, ACCESS_TOKEN);

    assertThat(result).isTrue();
    verify(restTemplate)
        .put(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            mapRequestCaptor.capture());
    assertThat(mapRequestCaptor.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer " + ACCESS_TOKEN);
    assertThat(mapRequestCaptor.getValue().getBody().get("users"))
        .isEqualTo(Map.of("@other:matrix.example", 100, USER_ID, 50));
  }

  @Test
  void setUserPowerLevel_ShouldCreateUsersMap_WhenPowerLevelsDoNotContainUsers() {
    var currentPowerLevels = new HashMap<String, Object>();
    when(restTemplate.exchange(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(currentPowerLevels));

    var result = matrixRoomClient.setUserPowerLevel(ROOM_ID, USER_ID, 50, ACCESS_TOKEN);

    assertThat(result).isTrue();
    verify(restTemplate)
        .put(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            mapRequestCaptor.capture());
    assertThat(mapRequestCaptor.getValue().getBody().get("users")).isEqualTo(Map.of(USER_ID, 50));
  }

  @Test
  void getPowerLevels_ShouldReturnTheRoomContentReadWithTheGivenToken() {
    var content = new HashMap<String, Object>(Map.of("events_default", 0));
    when(restTemplate.exchange(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(content));

    assertThat(matrixRoomClient.getPowerLevels(ROOM_ID, ACCESS_TOKEN)).contains(content);
  }

  @Test
  void getPowerLevels_ShouldBeEmpty_WhenMatrixRefusesOrAnswersNothing() {
    when(restTemplate.exchange(
            org.mockito.ArgumentMatchers.any(java.net.URI.class),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(null))
        .thenThrow(new RuntimeException("refused"));

    assertThat(matrixRoomClient.getPowerLevels(ROOM_ID, ACCESS_TOKEN)).isEmpty();
    assertThat(matrixRoomClient.getPowerLevels(ROOM_ID, ACCESS_TOKEN)).isEmpty();
  }

  @Test
  void setUserPowerLevel_ShouldReturnFalse_WhenCurrentPowerLevelsBodyIsNull() {
    when(restTemplate.exchange(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(null));

    assertThat(matrixRoomClient.setUserPowerLevel(ROOM_ID, USER_ID, 50, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void setUserPowerLevel_ShouldReturnFalse_WhenMatrixRejectsPowerLevelUpdate() {
    when(restTemplate.exchange(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("users", Map.of())));
    doThrow(new RuntimeException("update failed"))
        .when(restTemplate)
        .put(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class));

    assertThat(matrixRoomClient.setUserPowerLevel(ROOM_ID, USER_ID, 50, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void setUserPowerLevel_ShouldReturnFalse_WhenMatrixRejectsPowerLevelRead() {
    when(restTemplate.exchange(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{\"error\":\"not allowed\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThat(matrixRoomClient.setUserPowerLevel(ROOM_ID, USER_ID, 50, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void setUserPowerLevel_ShouldPreserveNumericValuesReturnedByJackson() {
    var currentPowerLevels = new HashMap<String, Object>();
    currentPowerLevels.put("users", Map.of("@other:matrix.example", 100L));
    when(restTemplate.exchange(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(currentPowerLevels));

    var result = matrixRoomClient.setUserPowerLevel(ROOM_ID, USER_ID, 50, ACCESS_TOKEN);

    assertThat(result).isTrue();
    verify(restTemplate)
        .put(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.power_levels")),
            mapRequestCaptor.capture());
    assertThat(mapRequestCaptor.getValue().getBody().get("users"))
        .isEqualTo(Map.of("@other:matrix.example", 100L, USER_ID, 50));
  }

  @Test
  void removeUserFromRoom_ShouldSendLeaveMembershipEvent() {
    var result = matrixRoomClient.removeUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN);

    assertThat(result).isTrue();
    verify(restTemplate)
        .put(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.member/"
                        + ENCODED_USER_ID)),
            mapRequestCaptor.capture());
    assertThat(mapRequestCaptor.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer " + ACCESS_TOKEN);
    assertThat(mapRequestCaptor.getValue().getBody()).isEqualTo(Map.of("membership", "leave"));
  }

  @Test
  void removeUserFromRoom_ShouldReturnFalse_WhenMatrixRejectsMembershipUpdate() {
    doThrow(new RuntimeException("remove failed"))
        .when(restTemplate)
        .put(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.member/"
                        + ENCODED_USER_ID)),
            org.mockito.ArgumentMatchers.any(HttpEntity.class));

    assertThat(matrixRoomClient.removeUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void removeUserFromRoom_ShouldReturnFalse_WhenMatrixReturnsClientError() {
    doThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{\"error\":\"not allowed\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8))
        .when(restTemplate)
        .put(
            eq(
                uri(
                    API_URL
                        + "/_matrix/client/r0/rooms/"
                        + ENCODED_ROOM_ID
                        + "/state/m.room.member/"
                        + ENCODED_USER_ID)),
            org.mockito.ArgumentMatchers.any(HttpEntity.class));

    assertThat(matrixRoomClient.removeUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN)).isFalse();
  }

  // ── banUserFromRoom ─────────────────────────────────────────────────────────

  @Test
  void banUserFromRoom_ShouldPostBanWithUserId_AndReturnTrue() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/ban")),
            mapRequestCaptor.capture(),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()));

    assertThat(matrixRoomClient.banUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN)).isTrue();
    assertThat(mapRequestCaptor.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer " + ACCESS_TOKEN);
    assertThat(mapRequestCaptor.getValue().getBody()).isEqualTo(Map.of("user_id", USER_ID));
  }

  @Test
  void banUserFromRoom_ShouldReturnFalse_WhenMatrixRejectsBan() {
    doThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{\"error\":\"no power\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8))
        .when(restTemplate)
        .postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/ban")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class));

    assertThat(matrixRoomClient.banUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void banUserFromRoom_ShouldReturnFalse_WhenMatrixThrows() {
    doThrow(new RuntimeException("synapse down"))
        .when(restTemplate)
        .postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/ban")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class));

    assertThat(matrixRoomClient.banUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN)).isFalse();
  }

  // ── unbanUserFromRoom ───────────────────────────────────────────────────────

  @Test
  void unbanUserFromRoom_ShouldPostUnban_AndReturnTrue() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/unban")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()));

    assertThat(matrixRoomClient.unbanUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN)).isTrue();
  }

  @Test
  void unbanUserFromRoom_ShouldTreatNotBannedAsSuccess() {
    doThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{\"error\":\"not banned\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8))
        .when(restTemplate)
        .postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/unban")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class));

    assertThat(matrixRoomClient.unbanUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN)).isTrue();
  }

  // ── leaveRoom ───────────────────────────────────────────────────────────────

  @Test
  void leaveRoom_success_returnsTrue() {
    // Leaving a room is best-effort; a 2xx from Synapse means the user is no longer a member.
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/leave")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()));

    assertThat(matrixRoomClient.leaveRoom(ROOM_ID, ACCESS_TOKEN)).isTrue();
  }

  @Test
  void leaveRoom_forbidden_returnsTrueWhenUserAlreadyLeft() {
    // A 403 on leave means the desired end state (not in room) already holds.
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/leave")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThat(matrixRoomClient.leaveRoom(ROOM_ID, ACCESS_TOKEN)).isTrue();
  }

  @Test
  void leaveRoom_notFound_returnsTrueWhenRoomIsGone() {
    // A 404 on leave is treated as success because the user cannot be in a missing room.
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/leave")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                null,
                "{}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThat(matrixRoomClient.leaveRoom(ROOM_ID, ACCESS_TOKEN)).isTrue();
  }

  @Test
  void leaveRoom_otherClientError_returnsFalse() {
    // Non-recoverable client errors must surface as a failed leave for callers to handle.
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/leave")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                null,
                "{}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThat(matrixRoomClient.leaveRoom(ROOM_ID, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void leaveRoom_genericException_returnsFalse() {
    // Network failures during leave must not propagate as unchecked exceptions.
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/leave")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenThrow(new RuntimeException("connection reset"));

    assertThat(matrixRoomClient.leaveRoom(ROOM_ID, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void joinRoom_genericException_returnsFalse() {
    // Unexpected join failures must degrade to false so membership flows can retry.
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/join")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenThrow(new RuntimeException("connection reset"));

    assertThat(matrixRoomClient.joinRoom(ROOM_ID, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void unbanUserFromRoom_otherClientError_returnsFalse() {
    // A genuine unban rejection (not "not banned") must be reported as failure.
    doThrow(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                null,
                "{\"error\":\"denied\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8))
        .when(restTemplate)
        .postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/unban")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class));

    assertThat(matrixRoomClient.unbanUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void unbanUserFromRoom_genericException_returnsFalse() {
    // Unban is best-effort; transport errors must not bubble up to callers.
    doThrow(new RuntimeException("synapse down"))
        .when(restTemplate)
        .postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/unban")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class));

    assertThat(matrixRoomClient.unbanUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void banUserFromRoom_nonSuccessResponse_returnsFalse() {
    // A non-2xx ban response without an exception must still be treated as failure.
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_matrix/client/r0/rooms/" + ENCODED_ROOM_ID + "/ban")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of()));

    assertThat(matrixRoomClient.banUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN)).isFalse();
  }

  // ── forceJoinUser / makeRoomAdmin (Synapse admin API, #1422) ────────────────

  private static final String ADMIN_TOKEN = "admin-token";

  @Test
  void forceJoinUser_ShouldPostUserIdToTheAdminJoinEndpointWithTheAdminToken() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_synapse/admin/v1/join/" + ENCODED_ROOM_ID)),
            mapRequestCaptor.capture(),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("room_id", ROOM_ID)));

    assertThat(matrixRoomClient.forceJoinUser(ROOM_ID, USER_ID, ADMIN_TOKEN)).isTrue();

    assertThat(mapRequestCaptor.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer " + ADMIN_TOKEN);
    assertThat(mapRequestCaptor.getValue().getBody()).isEqualTo(Map.of("user_id", USER_ID));
  }

  @Test
  void forceJoinUser_ShouldReturnFalse_WhenSynapseRefusesTheJoin() {
    doThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                null,
                "{\"error\":\"not in room\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8))
        .when(restTemplate)
        .postForEntity(
            eq(uri(API_URL + "/_synapse/admin/v1/join/" + ENCODED_ROOM_ID)),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class));

    assertThat(matrixRoomClient.forceJoinUser(ROOM_ID, USER_ID, ADMIN_TOKEN)).isFalse();
  }

  @Test
  void forceJoinUser_ShouldReturnFalse_WhenSynapseIsUnreachable() {
    doThrow(new RuntimeException("synapse down"))
        .when(restTemplate)
        .postForEntity(
            eq(uri(API_URL + "/_synapse/admin/v1/join/" + ENCODED_ROOM_ID)),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class));

    assertThat(matrixRoomClient.forceJoinUser(ROOM_ID, USER_ID, ADMIN_TOKEN)).isFalse();
  }

  @Test
  void makeRoomAdmin_ShouldPostUserIdToTheMakeRoomAdminEndpointWithTheAdminToken() {
    when(restTemplate.postForEntity(
            eq(uri(API_URL + "/_synapse/admin/v1/rooms/" + ENCODED_ROOM_ID + "/make_room_admin")),
            mapRequestCaptor.capture(),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()));

    assertThat(matrixRoomClient.makeRoomAdmin(ROOM_ID, USER_ID, ADMIN_TOKEN)).isTrue();

    assertThat(mapRequestCaptor.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer " + ADMIN_TOKEN);
    assertThat(mapRequestCaptor.getValue().getBody()).isEqualTo(Map.of("user_id", USER_ID));
  }

  @Test
  void makeRoomAdmin_ShouldReturnFalse_WhenNoLocalMemberCanChangePowerLevels() {
    doThrow(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                null,
                "{\"error\":\"No local admin user in room with power to update power levels.\"}"
                    .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8))
        .when(restTemplate)
        .postForEntity(
            eq(uri(API_URL + "/_synapse/admin/v1/rooms/" + ENCODED_ROOM_ID + "/make_room_admin")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class));

    assertThat(matrixRoomClient.makeRoomAdmin(ROOM_ID, USER_ID, ADMIN_TOKEN)).isFalse();
  }

  @Test
  void makeRoomAdmin_ShouldReturnFalse_WhenSynapseIsUnreachable() {
    doThrow(new RuntimeException("synapse down"))
        .when(restTemplate)
        .postForEntity(
            eq(uri(API_URL + "/_synapse/admin/v1/rooms/" + ENCODED_ROOM_ID + "/make_room_admin")),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class));

    assertThat(matrixRoomClient.makeRoomAdmin(ROOM_ID, USER_ID, ADMIN_TOKEN)).isFalse();
  }
}
