package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.DATABASE;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.SessionTopicRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.lifecycle.LifecycleEventPublisher;
import de.caritas.cob.userservice.api.service.lifecycle.LifecycleEventPublisher.DeletedSession;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.model.SessionDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.service.TeamDiscussionPurgeService;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeleteSingleRoomAndSessionActionTest {

  @InjectMocks private DeleteSingleRoomAndSessionAction deleteSingleRoomAndSessionAction;

  @Mock private SessionRepository sessionRepository;

  @Mock private SessionDataRepository sessionDataRepository;

  @Mock private CaseHandoverRequestRepository caseHandoverRequestRepository;

  @Mock private SessionSupervisorRepository sessionSupervisorRepository;

  @Mock private SessionTopicRepository sessionTopicRepository;

  @Mock private TeamDiscussionRepository teamDiscussionRepository;

  @Mock private TeamDiscussionPurgeService teamDiscussionPurgeService;

  @Mock private LifecycleEventPublisher lifecycleEventPublisher;

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
    verify(this.sessionDataRepository, times(1)).findBySessionId(session.getId());
    verify(this.sessionDataRepository, times(1)).deleteAll(any());
    verify(this.caseHandoverRequestRepository, times(1)).deleteAllBySessionId(session.getId());
    verify(this.sessionSupervisorRepository, times(1)).deleteAllBySessionId(session.getId());
    verify(this.sessionTopicRepository, times(1)).deleteAllBySessionId(session.getId());
    verify(this.sessionRepository, times(1)).delete(session);
  }

  @Test
  void execute_Should_returnExpectedWorkflowErrors_When_noUserSessionDeletedStepIsSuccessful()
      throws Exception {
    Session session = new EasyRandom().nextObject(Session.class);
    doThrow(new RuntimeException()).when(this.sessionDataRepository).deleteAll(any());
    doThrow(new RuntimeException())
        .when(this.caseHandoverRequestRepository)
        .deleteAllBySessionId(any());
    doThrow(new RuntimeException())
        .when(this.sessionSupervisorRepository)
        .deleteAllBySessionId(any());
    doThrow(new RuntimeException()).when(this.sessionRepository).delete(any());
    SessionDeletionWorkflowDTO workflowDTO =
        new SessionDeletionWorkflowDTO(session, new ArrayList<>());

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(4));
    assertThat(logCaptor.count(Level.ERROR)).isEqualTo(4);
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
  void execute_Should_returnExpectedWorkflowError_When_caseHandoverRequestDeletionFails() {
    Session session = new EasyRandom().nextObject(Session.class);
    doThrow(new RuntimeException())
        .when(this.caseHandoverRequestRepository)
        .deleteAllBySessionId(any());
    SessionDeletionWorkflowDTO workflowDTO =
        new SessionDeletionWorkflowDTO(session, new ArrayList<>());

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(logCaptor.contains(Level.ERROR, "UserService delete workflow error")).isTrue();
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(ASKER));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(DATABASE));
    assertThat(workflowErrors.get(0).getIdentifier(), is(session.getId().toString()));
    assertThat(
        workflowErrors.get(0).getReason(),
        is("Unable to delete case handover requests for session"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
  }

  @Test
  void execute_Should_returnExpectedWorkflowError_When_sessionSupervisorDeletionFails() {
    Session session = new EasyRandom().nextObject(Session.class);
    doThrow(new RuntimeException())
        .when(this.sessionSupervisorRepository)
        .deleteAllBySessionId(any());
    SessionDeletionWorkflowDTO workflowDTO =
        new SessionDeletionWorkflowDTO(session, new ArrayList<>());

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);

    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(1));
    assertThat(
        workflowDTO.getDeletionWorkflowErrors().get(0).getReason(),
        is("Unable to delete supervisors for session"));
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

  /** #1118: the team discussion of the session must go with it, room and rows alike. */
  @Test
  void execute_Should_purgeTeamDiscussionBeforeDeletingSession_When_sessionHasTeamDiscussion() {
    Session session = new EasyRandom().nextObject(Session.class);
    TeamDiscussion discussion =
        TeamDiscussion.builder()
            .id(7L)
            .sessionId(session.getId())
            .matrixRoomId("!discussion:matrix.example.com")
            .build();
    when(this.teamDiscussionRepository.findBySessionId(session.getId()))
        .thenReturn(Optional.of(discussion));
    SessionDeletionWorkflowDTO workflowDTO =
        new SessionDeletionWorkflowDTO(session, new ArrayList<>());

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);

    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
    InOrder order = inOrder(this.teamDiscussionPurgeService, this.sessionRepository);
    order
        .verify(this.teamDiscussionPurgeService)
        .purge(discussion, workflowDTO.getDeletionWorkflowErrors());
    order.verify(this.sessionRepository).delete(session);
  }

  @Test
  void execute_Should_notTouchPurgeService_When_sessionHasNoTeamDiscussion() {
    Session session = new EasyRandom().nextObject(Session.class);
    when(this.teamDiscussionRepository.findBySessionId(session.getId()))
        .thenReturn(Optional.empty());
    SessionDeletionWorkflowDTO workflowDTO =
        new SessionDeletionWorkflowDTO(session, new ArrayList<>());

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);

    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
    verify(this.teamDiscussionPurgeService, never()).purge(any(), any());
    verify(this.sessionRepository).delete(session);
  }

  @Test
  void execute_Should_returnExpectedWorkflowError_When_teamDiscussionLookupFails() {
    Session session = new EasyRandom().nextObject(Session.class);
    doThrow(new RuntimeException()).when(this.teamDiscussionRepository).findBySessionId(any());
    SessionDeletionWorkflowDTO workflowDTO =
        new SessionDeletionWorkflowDTO(session, new ArrayList<>());

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(logCaptor.contains(Level.ERROR, "UserService delete workflow error")).isTrue();
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(ASKER));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(DATABASE));
    assertThat(workflowErrors.get(0).getIdentifier(), is(session.getId().toString()));
    assertThat(
        workflowErrors.get(0).getReason(), is("Unable to delete team discussion for session"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
    verify(this.sessionRepository).delete(session);
  }

  @Test
  void execute_Should_publishSessionDeleted_When_theSessionWasDeleted() {
    Session session = new EasyRandom().nextObject(Session.class);

    this.deleteSingleRoomAndSessionAction.execute(
        new SessionDeletionWorkflowDTO(session, new ArrayList<>()));

    // ADR-034: announced only after the row is gone, with the ids captured before.
    InOrder order = inOrder(this.sessionRepository, this.lifecycleEventPublisher);
    order.verify(this.sessionRepository).delete(session);
    order
        .verify(this.lifecycleEventPublisher)
        .sessionDeleted(
            new DeletedSession(
                session.getTenantId(),
                session.getId(),
                session.getUser().getUserId(),
                session.getAgencyId()));
  }

  @Test
  void execute_Should_notPublishSessionDeleted_When_theSessionCouldNotBeDeleted() {
    Session session = new EasyRandom().nextObject(Session.class);
    doThrow(new RuntimeException()).when(this.sessionRepository).delete(any());

    this.deleteSingleRoomAndSessionAction.execute(
        new SessionDeletionWorkflowDTO(session, new ArrayList<>()));

    verify(this.lifecycleEventPublisher, never()).sessionDeleted(any());
  }

  @Test
  void execute_Should_keepTheSession_When_aChildStepFailsAndTheFlagIsSet() {
    Session session = new EasyRandom().nextObject(Session.class);
    doThrow(new RuntimeException()).when(this.sessionTopicRepository).deleteAllBySessionId(any());
    var workflowDTO = new SessionDeletionWorkflowDTO(session, new ArrayList<>(), true);

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);

    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(1));
    verify(this.sessionRepository, never()).delete(any());
    verify(this.lifecycleEventPublisher, never()).sessionDeleted(any());
  }

  @Test
  void execute_Should_deleteTheSession_When_everyChildStepSucceedsAndTheFlagIsSet() {
    Session session = new EasyRandom().nextObject(Session.class);
    var workflowDTO = new SessionDeletionWorkflowDTO(session, new ArrayList<>(), true);

    this.deleteSingleRoomAndSessionAction.execute(workflowDTO);

    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
    verify(this.sessionRepository).delete(session);
    verify(this.lifecycleEventPublisher).sessionDeleted(any());
  }

  @Test
  void execute_Should_stillDeleteTheSession_When_aChildStepFailsWithoutTheFlag() {
    Session session = new EasyRandom().nextObject(Session.class);
    doThrow(new RuntimeException()).when(this.sessionTopicRepository).deleteAllBySessionId(any());

    this.deleteSingleRoomAndSessionAction.execute(
        new SessionDeletionWorkflowDTO(session, new ArrayList<>()));

    verify(this.sessionRepository).delete(session);
  }
}
