package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.DATABASE;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.ROCKET_CHAT;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatDeleteGroupException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.model.SessionDeletionWorkflowDTO;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.ArrayList;
import java.util.List;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeleteSingleRoomAndSessionActionTest {

  @InjectMocks private DeleteSingleRoomAndSessionAction deleteSingleRoomAndSessionAction;

  @Mock private SessionRepository sessionRepository;

  @Mock private SessionDataRepository sessionDataRepository;

  @Mock private RocketChatService rocketChatService;

  private LogbackCaptor logCaptor;

  @BeforeEach
  void setup() {
    logCaptor = LogbackCaptor.forClass(DeleteRoomsAndSessionAction.class);
  }

  @AfterEach
  void tearDown() {
    logCaptor.detach();
  }

  @Test
  void execute_Should_returnEmptyListAndPerformAllDeletions_When_userSessionIsDeletedSuccessful()
      throws Exception {
    Session session = new EasyRandom().nextObject(Session.class);
    SessionDeletionWorkflowDTO workflowDTO = new SessionDeletionWorkflowDTO(session, emptyList());

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(0));
    assertThat(logCaptor.events()).isEmpty();
    verify(this.rocketChatService, times(1)).deleteGroupAsTechnicalUser(any());
    verify(this.sessionDataRepository, times(1)).findBySessionId(session.getId());
    verify(this.sessionDataRepository, times(1)).deleteAll(any());
    verify(this.sessionRepository, times(1)).delete(session);
  }

  @Test
  void execute_Should_returnExpectedWorkflowErrors_When_noUserSessionDeletedStepIsSuccessful()
      throws Exception {
    Session session = new EasyRandom().nextObject(Session.class);
    doThrow(new RocketChatDeleteGroupException(new RuntimeException()))
        .when(this.rocketChatService)
        .deleteGroupAsTechnicalUser(any());
    doThrow(new RuntimeException()).when(this.sessionDataRepository).deleteAll(any());
    doThrow(new RuntimeException()).when(this.sessionRepository).delete(any());
    SessionDeletionWorkflowDTO workflowDTO =
        new SessionDeletionWorkflowDTO(session, new ArrayList<>());

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(3));
    assertThat(logCaptor.count(Level.ERROR)).isEqualTo(3);
  }

  @Test
  void execute_Should_returnExpectedWorkflowErrors_When_rocketChatDeletionFails() throws Exception {
    Session session = new EasyRandom().nextObject(Session.class);
    doThrow(new RocketChatDeleteGroupException(new RuntimeException()))
        .when(this.rocketChatService)
        .deleteGroupAsTechnicalUser(any());
    SessionDeletionWorkflowDTO workflowDTO =
        new SessionDeletionWorkflowDTO(session, new ArrayList<>());

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(logCaptor.count(Level.ERROR)).isEqualTo(1);
    assertThat(logCaptor.contains(Level.ERROR, "UserService delete workflow error")).isTrue();
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(ASKER));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(ROCKET_CHAT));
    assertThat(workflowErrors.get(0).getIdentifier(), is(session.getGroupId()));
    assertThat(workflowErrors.get(0).getReason(), is("Deletion of Rocket.Chat group failed"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
  }

  @Test
  void execute_Should_returnExpectedWorkflowError_When_sessionDataDeletionFails() {
    Session session = new EasyRandom().nextObject(Session.class);
    doThrow(new RuntimeException()).when(this.sessionDataRepository).deleteAll(any());
    SessionDeletionWorkflowDTO workflowDTO =
        new SessionDeletionWorkflowDTO(session, new ArrayList<>());

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(logCaptor.contains(Level.ERROR, "UserService delete workflow error")).isTrue();
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(ASKER));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(DATABASE));
    assertThat(workflowErrors.get(0).getIdentifier(), is(session.getId().toString()));
    assertThat(workflowErrors.get(0).getReason(), is("Unable to delete session data from session"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
  }

  @Test
  void execute_Should_returnExpectedWorkflowError_When_sessionDeletionFails() {
    Session session = new EasyRandom().nextObject(Session.class);
    doThrow(new RuntimeException()).when(this.sessionRepository).delete(any());
    SessionDeletionWorkflowDTO workflowDTO =
        new SessionDeletionWorkflowDTO(session, new ArrayList<>());

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(logCaptor.contains(Level.ERROR, "UserService delete workflow error")).isTrue();
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(ASKER));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(DATABASE));
    assertThat(workflowErrors.get(0).getIdentifier(), is(session.getId().toString()));
    assertThat(workflowErrors.get(0).getReason(), is("Unable to delete session"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
  }
}
