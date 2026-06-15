package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserResponseDTO;
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

  @Mock private MatrixConfig matrixConfig;
  @Mock private RestTemplate restTemplate;

  @Captor private ArgumentCaptor<HttpEntity<MatrixCreateRoomRequestDTO>> createRoomRequestCaptor;
  @Captor private ArgumentCaptor<HttpEntity<MatrixInviteUserRequestDTO>> inviteRequestCaptor;
  @Captor private ArgumentCaptor<HttpEntity<Map<String, Object>>> mapRequestCaptor;

  @InjectMocks private MatrixRoomClient matrixRoomClient;

  @BeforeEach
  void setup() {
    when(matrixConfig.getApiUrl(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> API_URL + invocation.getArgument(0, String.class));
  }

  @Test
  void createRoom_ShouldSendPrivateRoomCreationRequest() throws Exception {
    var responseBody = new MatrixCreateRoomResponseDTO();
    responseBody.setRoomId(ROOM_ID);
    when(restTemplate.postForEntity(
            eq(API_URL + "/_matrix/client/r0/createRoom"),
            createRoomRequestCaptor.capture(),
            eq(MatrixCreateRoomResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(responseBody));

    var response = matrixRoomClient.createRoom("Room name", "room-alias", ACCESS_TOKEN);

    assertThat(response.getBody()).isSameAs(responseBody);
    var request = createRoomRequestCaptor.getValue();
    assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer " + ACCESS_TOKEN);
    assertThat(request.getBody().getName()).isEqualTo("Room name");
    assertThat(request.getBody().getRoomAliasName()).isEqualTo("room-alias");
    assertThat(request.getBody().getPreset()).isEqualTo("private_chat");
    assertThat(request.getBody().getVisibility()).isEqualTo("private");
    assertThat(request.getBody().getInitialState()).isEmpty();
  }

  @Test
  void createRoom_ShouldThrowMatrixCreateRoomException_WhenMatrixRejectsRequest() {
    when(restTemplate.postForEntity(
            eq(API_URL + "/_matrix/client/r0/createRoom"),
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
  }

  @Test
  void inviteUserToRoom_ShouldSendInviteRequest() throws Exception {
    var responseBody = new MatrixInviteUserResponseDTO();
    when(restTemplate.postForEntity(
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/invite"),
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
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/invite"),
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
  void joinRoom_ShouldReturnTrue_WhenMatrixJoinSucceeds() {
    when(restTemplate.postForEntity(
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/join"),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()));

    assertThat(matrixRoomClient.joinRoom(ROOM_ID, ACCESS_TOKEN)).isTrue();
  }

  @Test
  void joinRoom_ShouldReturnTrue_WhenMatrixReportsUserAlreadyJoined() {
    when(restTemplate.postForEntity(
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/join"),
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
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/join"),
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
  void setUserPowerLevel_ShouldPreserveExistingUsersAndUpdateTargetUser() {
    var currentUsers = new HashMap<String, Integer>();
    currentUsers.put("@other:matrix.example", 100);
    var currentPowerLevels = new HashMap<String, Object>();
    currentPowerLevels.put("users", currentUsers);
    when(restTemplate.exchange(
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/state/m.room.power_levels"),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(currentPowerLevels));

    var result = matrixRoomClient.setUserPowerLevel(ROOM_ID, USER_ID, 50, ACCESS_TOKEN);

    assertThat(result).isTrue();
    verify(restTemplate)
        .put(
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/state/m.room.power_levels"),
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
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/state/m.room.power_levels"),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(currentPowerLevels));

    var result = matrixRoomClient.setUserPowerLevel(ROOM_ID, USER_ID, 50, ACCESS_TOKEN);

    assertThat(result).isTrue();
    verify(restTemplate)
        .put(
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/state/m.room.power_levels"),
            mapRequestCaptor.capture());
    assertThat(mapRequestCaptor.getValue().getBody().get("users")).isEqualTo(Map.of(USER_ID, 50));
  }

  @Test
  void setUserPowerLevel_ShouldReturnFalse_WhenCurrentPowerLevelsBodyIsNull() {
    when(restTemplate.exchange(
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/state/m.room.power_levels"),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(null));

    assertThat(matrixRoomClient.setUserPowerLevel(ROOM_ID, USER_ID, 50, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void setUserPowerLevel_ShouldReturnFalse_WhenMatrixRejectsPowerLevelUpdate() {
    when(restTemplate.exchange(
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/state/m.room.power_levels"),
            eq(HttpMethod.GET),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("users", Map.of())));
    doThrow(new RuntimeException("update failed"))
        .when(restTemplate)
        .put(
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/state/m.room.power_levels"),
            org.mockito.ArgumentMatchers.any(HttpEntity.class));

    assertThat(matrixRoomClient.setUserPowerLevel(ROOM_ID, USER_ID, 50, ACCESS_TOKEN)).isFalse();
  }

  @Test
  void removeUserFromRoom_ShouldSendLeaveMembershipEvent() {
    var result = matrixRoomClient.removeUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN);

    assertThat(result).isTrue();
    verify(restTemplate)
        .put(
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/state/m.room.member/" + USER_ID),
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
            eq(API_URL + "/_matrix/client/r0/rooms/" + ROOM_ID + "/state/m.room.member/" + USER_ID),
            org.mockito.ArgumentMatchers.any(HttpEntity.class));

    assertThat(matrixRoomClient.removeUserFromRoom(ROOM_ID, USER_ID, ACCESS_TOKEN)).isFalse();
  }
}
