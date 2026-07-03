package de.caritas.cob.userservice.api.adapters.rocketchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatLoginException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-004: the inert adapter must never talk to Rocket.Chat - read paths return empty results
 * without throwing, write paths are silent no-ops and login-style calls fail with the declared
 * exception.
 */
class DisabledRocketChatServiceTest {

  private RocketChatCredentialsProvider credentialsProvider;
  private RocketChatConfig rocketChatConfig;
  private RocketChatMapper mapper;
  private DisabledRocketChatService service;

  @BeforeEach
  void setUp() {
    credentialsProvider = mock(RocketChatCredentialsProvider.class);
    rocketChatConfig = mock(RocketChatConfig.class);
    mapper = mock(RocketChatMapper.class);
    service = new DisabledRocketChatService(credentialsProvider, rocketChatConfig, mapper);
  }

  @Test
  void readPathsShouldReturnEmptyResultsWithoutThrowing() throws Exception {
    assertThat(service.findUser("chat-user")).isEmpty();
    assertThat(service.findUserAndAddToCache("chat-user")).isEmpty();
    assertThat(service.getChatInfo("room")).isEmpty();
    assertThat(service.findMembers("chat")).isEmpty();
    assertThat(service.findAllChats("chat-user")).isEmpty();
    assertThat(service.findAllAvailableUserIds()).isEmpty();
    assertThat(service.isLoggedIn("chat-user")).isEmpty();
    assertThat(service.isAvailable("chat-user")).isEmpty();
    assertThat(service.getStandardMembersOfGroup("group")).isEmpty();
    assertThat(service.getChatUsers("chat")).isEmpty();
    assertThat(service.getMembersOfGroup("group")).isEmpty();
    assertThat(service.getSubscriptionsOfUser(null)).isEmpty();
    assertThat(service.getRoomsOfUser(null)).isEmpty();
    assertThat(service.fetchAllInactivePrivateGroupsSinceGivenDate(LocalDateTime.now())).isEmpty();
    assertThat(service.getRocketChatUserIdByUsername("username")).isNull();
    assertThat(service.getUserInfo("rc-user").getUser().getRooms()).isEmpty();
    assertThat(service.createPrivateGroup("group", null)).isEmpty();
    assertThat(service.createPrivateGroupWithSystemUser("group")).isEmpty();
  }

  @Test
  void writePathsShouldBeInertNoOps() {
    assertThatCode(
            () -> {
              service.updateCredentials();
              service.addUserToGroup("user", "group");
              service.addTechnicalUserToGroup("group");
              service.leaveFromGroupAsTechnicalUser("group");
              service.removeUserFromGroup("user", "group");
              service.removeUserFromGroupIgnoreGroupNotFound("user", "group");
              service.removeAllStandardUsersFromGroup("group");
              service.removeSystemMessages("group", LocalDateTime.now(), LocalDateTime.now());
              service.removeAllMessages("group");
              service.deleteUser("rc-user");
              service.deleteGroupAsTechnicalUser("group");
              service.setRoomReadOnly("room");
              service.setRoomWriteable("room");
              service.updateUser(null);
            })
        .doesNotThrowAnyException();

    assertThat(service.muteUserInChat("user", "room")).isTrue();
    assertThat(service.unmuteUserInChat("user", "room")).isTrue();
    assertThat(service.updateUser("user", "display")).isTrue();
    assertThat(service.setUserPresence("user", "online")).isTrue();
    assertThat(service.logoutUser(null)).isTrue();
    assertThat(service.removeUserFromSession("user", "chat")).isTrue();
    assertThat(service.deleteGroupAsSystemUser("group")).isTrue();
    assertThat(service.rollbackGroup("group", null)).isTrue();
    assertThat(service.saveRoomSettings("chat", true)).isTrue();
    assertThat(service.updateChatE2eKey("user", "room", "key")).isTrue();
  }

  @Test
  void loginStylePathsShouldThrowDeclaredLoginException() {
    assertThatThrownBy(() -> service.getUserID("username", "password", true))
        .isInstanceOf(RocketChatLoginException.class);
    assertThatThrownBy(() -> service.loginWithPassword("username", "password"))
        .isInstanceOf(RocketChatLoginException.class);
    assertThatThrownBy(() -> service.loginUserFirstTime("username", "password"))
        .isInstanceOf(RocketChatLoginException.class);
    assertThatThrownBy(() -> service.createUser("username", "password", "mail@example.com"))
        .isInstanceOf(RocketChatLoginException.class);
  }

  @Test
  void nothingShouldEverReachRocketChatCollaborators() throws Exception {
    service.updateCredentials();
    service.getUserInfo("rc-user");
    service.addUserToGroup("user", "group");
    service.getRoomsOfUser(null);

    verifyNoInteractions(credentialsProvider, rocketChatConfig, mapper);
  }
}
