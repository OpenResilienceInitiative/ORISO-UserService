package de.caritas.cob.userservice.api.adapters.rocketchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatLoginException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * C2 hardening: proves the Matrix-only path ({@code rocket-chat.enabled=false}) never reaches
 * Rocket.Chat, exercising the CONTRACT of the inert adapter at the service layer.
 *
 * <p>Where {@link
 * de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatDisabledBootTest} only proves
 * the CONTEXT boots Matrix-only, this test asserts the runtime behaviour every ORISO caller depends
 * on when Rocket.Chat is off:
 *
 * <ol>
 *   <li>the injected {@link RocketChatService} bean IS the inert {@link DisabledRocketChatService};
 *   <li>read methods return empty / never throw (no Rocket.Chat REST call is made);
 *   <li>login-style methods throw the declared {@link RocketChatLoginException} so callers hit
 *       their existing fallback instead of talking to a Rocket.Chat that is not there.
 * </ol>
 *
 * <p>This is a normal {@code *Test} (surefire, runs in CI) pinned to {@code
 * rocket-chat.enabled=false} with no Rocket.Chat URLs configured, so it closes the CI
 * integration-coverage gap deterministically without standing up any external service.
 */
@SpringBootTest(
    properties = {"rocket-chat.enabled=false", "rocket-chat.base-url=", "rocket-chat.mongo-url="})
@ActiveProfiles("testing")
class DisabledRocketChatServiceContractTest {

  @Autowired private RocketChatService rocketChatService;

  @Test
  void injectedRocketChatServiceShouldBeTheInertDisabledAdapter() {
    assertThat(rocketChatService).isInstanceOf(DisabledRocketChatService.class);
  }

  @Test
  void readMethodsShouldReturnEmptyAndNeverThrow() {
    assertThatCode(
            () -> {
              // group / room reads -> empty collections, never a Rocket.Chat REST call
              assertThat(rocketChatService.getMembersOfGroup("any-group")).isEmpty();
              assertThat(rocketChatService.getStandardMembersOfGroup("any-group")).isEmpty();
              assertThat(rocketChatService.getChatUsers("any-chat")).isEmpty();
              assertThat(rocketChatService.findMembers("any-chat")).isEmpty();
              assertThat(rocketChatService.getChatInfo("any-room")).isEmpty();
              assertThat(rocketChatService.findAllChats("any-user")).isEmpty();
              assertThat(rocketChatService.getRoomsOfUser(null)).isEmpty();
              assertThat(rocketChatService.getSubscriptionsOfUser(null)).isEmpty();

              // user reads -> empty optionals / ids, never a Rocket.Chat REST call
              assertThat(rocketChatService.findUser("any-user")).isEmpty();
              assertThat(rocketChatService.findAllAvailableUserIds()).isEmpty();
              assertThat(rocketChatService.isLoggedIn("any-user")).isEmpty();
              assertThat(rocketChatService.isAvailable("any-user")).isEmpty();
              assertThat(rocketChatService.getRocketChatUserIdByUsername("any-user")).isNull();

              // users.info returns an inert (non-null, success) shell rather than throwing
              var userInfo = rocketChatService.getUserInfo("rc-user-id");
              assertThat(userInfo).isNotNull();
              assertThat(userInfo.isSuccess()).isTrue();
              assertThat(userInfo.getUser().getId()).isEqualTo("rc-user-id");
            })
        .doesNotThrowAnyException();
  }

  @Test
  void loginStyleMethodsShouldThrowRocketChatLoginExceptionSoCallersFallBack() {
    assertThatExceptionOfType(RocketChatLoginException.class)
        .isThrownBy(() -> rocketChatService.getUserID("user", "password", true));

    assertThatExceptionOfType(RocketChatLoginException.class)
        .isThrownBy(() -> rocketChatService.loginWithPassword("user", "password"));

    assertThatExceptionOfType(RocketChatLoginException.class)
        .isThrownBy(() -> rocketChatService.loginUserFirstTime("user", "password"));

    assertThatExceptionOfType(RocketChatLoginException.class)
        .isThrownBy(() -> rocketChatService.createUser("user", "password", "user@example.com"));
  }

  @Test
  void writeMethodsShouldBeNoOpsAndNeverThrow() {
    assertThatCode(
            () -> {
              rocketChatService.addTechnicalUserToGroup("any-group");
              rocketChatService.addUserToGroup("any-user", "any-group");
              rocketChatService.removeUserFromGroup("any-user", "any-group");
              rocketChatService.deleteGroupAsTechnicalUser("any-group");
              rocketChatService.removeAllMessages("any-group");
              rocketChatService.setRoomReadOnly("any-room");
              // logout accepts a null credential object without a Rocket.Chat round-trip
              assertThat(rocketChatService.logoutUser((RocketChatCredentials) null)).isTrue();
            })
        .doesNotThrowAnyException();
  }
}
