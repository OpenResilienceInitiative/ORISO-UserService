package de.caritas.cob.userservice.api.actions.session;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SetRocketChatRoomReadOnlyActionCommandTest {

  @InjectMocks private SetRocketChatRoomReadOnlyActionCommand actionCommand;

  @Mock private RocketChatService rocketChatService;

  private LogbackCaptor logCaptor;

  @BeforeEach
  public void setup() {
    logCaptor = LogbackCaptor.forClass(SetRocketChatRoomReadOnlyActionCommand.class);
  }

  @AfterEach
  public void tearDown() {
    logCaptor.detach();
  }

  @ParameterizedTest
  @MethodSource("sessionsWithoutInteractionsExpected")
  void execute_Should_doNothing_When_sessionIsNullOrWithoutRcRooms(Session session) {
    this.actionCommand.execute(session);

    verifyNoMoreInteractions(this.rocketChatService);
  }

  private static List<Session> sessionsWithoutInteractionsExpected() {
    return asList(null, new Session());
  }

  @Test
  void execute_Should_useRocketChatService_When_sessionHasGroupId() throws Exception {
    Session session = new Session();
    session.setGroupId("group id");

    this.actionCommand.execute(session);

    verify(this.rocketChatService, times(1)).setRoomReadOnly("group id");
  }

  @Test
  void execute_Should_logError_When_rocketChatCallFails() throws Exception {
    Session session = new Session();
    session.setGroupId("group id");
    doThrow(new RuntimeException("")).when(this.rocketChatService).setRoomReadOnly(anyString());

    this.actionCommand.execute(session);

    assertThat(logCaptor.contains(Level.ERROR, "Rocket.Chat Error:")).isTrue();
  }
}
