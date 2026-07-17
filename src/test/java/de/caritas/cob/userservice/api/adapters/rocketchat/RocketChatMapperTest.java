package de.caritas.cob.userservice.api.adapters.rocketchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupMemberDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.PresenceDTO.PresenceStatus;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.PresenceListDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.PresenceOtherDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.Room;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomResponse;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.subscriptions.SubscriptionsGetDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.subscriptions.SubscriptionsUpdateDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.RocketChatUserDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UserInfoResponseDTO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class RocketChatMapperTest {

  private ObjectMapper objectMapper;
  private RocketChatMapper mapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    mapper = new RocketChatMapper(objectMapper);
  }

  @Test
  void muteUserOf_Should_produceSerializedMessageWithLowercaseUsername() {
    var result = mapper.muteUserOf("Alice", "room-1");

    assertThat(result.getMessage()).contains("\"method\":\"muteUserInRoom\"");
    assertThat(result.getMessage()).contains("alice");
    assertThat(result.getMessage()).contains("room-1");
  }

  @Test
  void unmuteUserOf_Should_useUnmuteMethod() {
    var result = mapper.unmuteUserOf("Bob", "room-2");

    assertThat(result.getMessage()).contains("\"method\":\"unmuteUserInRoom\"");
    assertThat(result.getMessage()).contains("bob");
  }

  @Test
  void muteUserOf_Should_leaveMessageNull_When_serializationFails() throws Exception {
    var broken = org.mockito.Mockito.mock(ObjectMapper.class);
    when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
    var brokenMapper = new RocketChatMapper(broken);

    var result = brokenMapper.muteUserOf("x", "r");

    assertThat(result.getMessage()).isNull();
  }

  @Test
  void setUserPresenceOf_Should_produceSerializedMessage() {
    var result = mapper.setUserPresenceOf("online");

    assertThat(result.getMessage()).contains("UserPresence:setDefaultStatus");
    assertThat(result.getMessage()).contains("online");
  }

  @Test
  void setUserPresenceOf_Should_throw_When_statusInvalid() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> mapper.setUserPresenceOf("bogus"));
  }

  @Test
  void setUserPresenceOf_Should_leaveMessageNull_When_serializationFails() throws Exception {
    var broken = org.mockito.Mockito.mock(ObjectMapper.class);
    when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
    var brokenMapper = new RocketChatMapper(broken);

    var result = brokenMapper.setUserPresenceOf("online");

    assertThat(result.getMessage()).isNull();
  }

  @Test
  void mapOfRoomResponse_Should_returnMapWithMutedUsers() {
    var room = new Room();
    room.setId("r-1");
    room.setMuted(List.of("a", "b"));
    var body = new RoomResponse();
    body.setRoom(room);

    var result = mapper.mapOfRoomResponse(ResponseEntity.ok(body));

    assertThat(result).isPresent();
    assertThat(result.get())
        .containsEntry("id", "r-1")
        .containsEntry("mutedUsers", List.of("a", "b"));
  }

  @Test
  void mapOfRoomResponse_Should_defaultToEmptyList_When_mutedIsNull() {
    var room = new Room();
    room.setId("r-2");
    room.setMuted(null);
    var body = new RoomResponse();
    body.setRoom(room);

    var result = mapper.mapOfRoomResponse(ResponseEntity.ok(body));

    assertThat(result).isPresent();
    assertThat((List<?>) result.get().get("mutedUsers")).isEmpty();
  }

  @Test
  void mapOfRoomResponse_Should_returnEmpty_When_bodyNull() {
    ResponseEntity<RoomResponse> response = ResponseEntity.ok(null);
    assertThat(mapper.mapOfRoomResponse(response)).isEmpty();
  }

  @Test
  void updateUserOf_Should_setUserIdAndDisplayName() {
    var updateUser = mapper.updateUserOf("chat-1", "Alice");

    assertThat(updateUser.getUserId()).isEqualTo("chat-1");
    assertThat(updateUser.getData().getName()).isEqualTo("Alice");
  }

  @Test
  void mapOfUserResponse_Should_returnMapWithIdUsernameDisplayName() {
    var user = new RocketChatUserDTO();
    user.setId("u-1");
    user.setUsername("alice");
    user.setName("Alice A.");
    var body = new UserInfoResponseDTO();
    body.setUser(user);

    var result = mapper.mapOfUserResponse(ResponseEntity.ok(body));

    assertThat(result).isPresent();
    assertThat(result.get())
        .containsEntry("id", "u-1")
        .containsEntry("username", "alice")
        .containsEntry("displayName", "Alice A.");
  }

  @Test
  void mapOfUserResponse_Should_omitOptionalFields_When_null() {
    var user = new RocketChatUserDTO();
    user.setId("u-2");
    var body = new UserInfoResponseDTO();
    body.setUser(user);

    var result = mapper.mapOfUserResponse(ResponseEntity.ok(body));

    assertThat(result).isPresent();
    assertThat(result.get()).containsOnlyKeys("id");
  }

  @Test
  void mapOfUserResponse_Should_returnEmpty_When_responseNull() {
    assertThat(mapper.mapOfUserResponse(null)).isEmpty();
  }

  @Test
  void mapOfUserResponse_Should_returnEmpty_When_bodyNull() {
    assertThat(mapper.mapOfUserResponse(ResponseEntity.ok(null))).isEmpty();
  }

  @Test
  void mapOfSubscriptionsResponse_Should_returnMapEntryPerUpdate() {
    var userA = new RocketChatUserDTO();
    userA.setId("user-a");
    var update = new SubscriptionsUpdateDTO();
    update.setUser(userA);
    update.setRoomId("room-a");
    update.setE2eKey("key-a");
    var body = new SubscriptionsGetDTO();
    body.setUpdate(new SubscriptionsUpdateDTO[] {update});

    var result = mapper.mapOfSubscriptionsResponse(ResponseEntity.ok(body));

    assertThat(result).isPresent();
    assertThat(result.get()).hasSize(1);
    assertThat(result.get().get(0))
        .containsEntry("userId", "user-a")
        .containsEntry("roomId", "room-a")
        .containsEntry("e2eKey", "key-a");
  }

  @Test
  void mapOfSubscriptionsResponse_Should_omitE2eKey_When_null() {
    var user = new RocketChatUserDTO();
    user.setId("u");
    var update = new SubscriptionsUpdateDTO();
    update.setUser(user);
    update.setRoomId("r");
    update.setE2eKey(null);
    var body = new SubscriptionsGetDTO();
    body.setUpdate(new SubscriptionsUpdateDTO[] {update});

    var result = mapper.mapOfSubscriptionsResponse(ResponseEntity.ok(body));

    assertThat(result).isPresent();
    assertThat(result.get().get(0)).doesNotContainKey("e2eKey");
  }

  @Test
  void mapOfSubscriptionsResponse_Should_returnEmpty_When_bodyNull() {
    assertThat(mapper.mapOfSubscriptionsResponse(ResponseEntity.ok(null))).isEmpty();
  }

  @Test
  void updateGroupKeyOf_Should_populateAllFields() {
    var result = mapper.updateGroupKeyOf("chat-1", "room-1", "key-1");

    assertThat(result.getUid()).isEqualTo("chat-1");
    assertThat(result.getRid()).isEqualTo("room-1");
    assertThat(result.getKey()).isEqualTo("key-1");
  }

  @Test
  void mapOf_members_Should_produceListOfChatUserIdMaps() {
    var m1 = new GroupMemberDTO();
    m1.set_id("id-1");
    var m2 = new GroupMemberDTO();
    m2.set_id("id-2");

    var result = mapper.mapOf(List.of(m1, m2));

    assertThat(result).containsExactly(Map.of("chatUserId", "id-1"), Map.of("chatUserId", "id-2"));
  }

  @Test
  void mapOfRoomSettings_Should_returnMapWithRidAndEncrypted() {
    var result = mapper.mapOfRoomSettings("room-1", true);

    assertThat(result).containsEntry("rid", "room-1").containsEntry("encrypted", true);
  }

  @Test
  void mapAvailableOf_Should_returnOnlyOnlineUserIds() {
    var online = new PresenceOtherDTO();
    online.setId("u-1");
    online.setStatus(PresenceStatus.ONLINE);
    var away = new PresenceOtherDTO();
    away.setId("u-2");
    away.setStatus(PresenceStatus.AWAY);
    var nullStatus = new PresenceOtherDTO();
    nullStatus.setId("u-3");
    var list = new PresenceListDTO();
    list.setUsers(List.of(online, away, nullStatus));

    var result = mapper.mapAvailableOf(list);

    assertThat(result).containsExactly("u-1");
  }
}
