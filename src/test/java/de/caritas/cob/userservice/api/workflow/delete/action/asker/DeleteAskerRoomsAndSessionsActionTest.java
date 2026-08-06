package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.DATABASE;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.SessionTopicRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
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
class DeleteAskerRoomsAndSessionsActionTest {

  @InjectMocks private DeleteAskerRoomsAndSessionsAction deleteAskerRoomsAndSessionsAction;

  @Mock private SessionRepository sessionRepository;

  @Mock private SessionDataRepository sessionDataRepository;

  @Mock private CaseHandoverRequestRepository caseHandoverRequestRepository;

  @Mock private SessionSupervisorRepository sessionSupervisorRepository;

  @Mock private SessionTopicRepository sessionTopicRepository;

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
  void execute_Should_returnEmptyListAndPerformNoDeletions_When_userHasNoSession() {
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(new User(), emptyList());

    this.deleteAskerRoomsAndSessionsAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(0));
    verifyNoMoreInteractions(this.sessionDataRepository);
    assertThat(logCaptor.events()).isEmpty();
  }

  @Test
  void execute_Should_returnEmptyListAndPerformAllDeletions_When_userSessionIsDeletedSuccessful()
      throws Exception {
    Session session = new EasyRandom().nextObject(Session.class);
    when(this.sessionRepository.findByUser(any())).thenReturn(singletonList(session));
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(new User(), emptyList());

    this.deleteAskerRoomsAndSessionsAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(0));
    assertThat(logCaptor.events()).isEmpty();
    verify(this.sessionDataRepository, times(1)).findBySessionId(session.getId());
    verify(this.sessionDataRepository, times(1)).deleteAll(any());
    verify(this.caseHandoverRequestRepository, times(1)).deleteAllBySessionId(session.getId());
    verify(this.sessionSupervisorRepository, times(1)).deleteAllBySessionId(session.getId());
    verify(this.sessionTopicRepository, times(1)).deleteAllBySessionId(session.getId());
    verify(this.sessionRepository, times(1)).delete(session);
    InOrder dependencyOrder = inOrder(this.sessionTopicRepository, this.sessionRepository);
    dependencyOrder.verify(this.sessionTopicRepository).deleteAllBySessionId(session.getId());
    dependencyOrder.verify(this.sessionRepository).delete(session);
  }

  @Test
  void execute_Should_returnExpectedWorkflowErrors_When_noUserSessionDeletedStepIsSuccessful()
      throws Exception {
    Session session = new EasyRandom().nextObject(Session.class);
    when(this.sessionRepository.findByUser(any())).thenReturn(singletonList(session));
    doThrow(new RuntimeException()).when(this.sessionDataRepository).deleteAll(any());
    doThrow(new RuntimeException())
        .when(this.caseHandoverRequestRepository)
        .deleteAllBySessionId(any());
    doThrow(new RuntimeException())
        .when(this.sessionSupervisorRepository)
        .deleteAllBySessionId(any());
    doThrow(new RuntimeException()).when(this.sessionTopicRepository).deleteAllBySessionId(any());
    doThrow(new RuntimeException()).when(this.sessionRepository).delete(any());
    AskerDeletionWorkflowDTO workflowDTO =
        new AskerDeletionWorkflowDTO(new User(), new ArrayList<>());

    this.deleteAskerRoomsAndSessionsAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(5));
    assertThat(logCaptor.count(Level.ERROR)).isEqualTo(5);
  }

  @Test
  void execute_Should_returnExpectedAmountOfWorkflowErrors_When_manySessionDeletionsFailed()
      throws Exception {
    List<Session> sessions =
        new EasyRandom().objects(Session.class, 3).collect(Collectors.toList());
    when(this.sessionRepository.findByUser(any())).thenReturn(sessions);
    doThrow(new RuntimeException()).when(this.sessionDataRepository).deleteAll(any());
    doThrow(new RuntimeException())
        .when(this.caseHandoverRequestRepository)
        .deleteAllBySessionId(any());
    doThrow(new RuntimeException())
        .when(this.sessionSupervisorRepository)
        .deleteAllBySessionId(any());
    doThrow(new RuntimeException()).when(this.sessionTopicRepository).deleteAllBySessionId(any());
    doThrow(new RuntimeException()).when(this.sessionRepository).delete(any());
    AskerDeletionWorkflowDTO workflowDTO =
        new AskerDeletionWorkflowDTO(new User(), new ArrayList<>());

    this.deleteAskerRoomsAndSessionsAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(15));
    assertThat(logCaptor.count(Level.ERROR)).isEqualTo(15);
  }

  @Test
  void execute_Should_returnExpectedWorkflowError_When_sessionDataDeletionFails() {
    Session session = new EasyRandom().nextObject(Session.class);
    when(this.sessionRepository.findByUser(any())).thenReturn(singletonList(session));
    doThrow(new RuntimeException()).when(this.sessionDataRepository).deleteAll(any());
    AskerDeletionWorkflowDTO workflowDTO =
        new AskerDeletionWorkflowDTO(new User(), new ArrayList<>());

    this.deleteAskerRoomsAndSessionsAction.execute(workflowDTO);
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
    when(this.sessionRepository.findByUser(any())).thenReturn(singletonList(session));
    doThrow(new RuntimeException())
        .when(this.caseHandoverRequestRepository)
        .deleteAllBySessionId(any());
    AskerDeletionWorkflowDTO workflowDTO =
        new AskerDeletionWorkflowDTO(new User(), new ArrayList<>());

    this.deleteAskerRoomsAndSessionsAction.execute(workflowDTO);
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
  void execute_Should_returnExpectedWorkflowError_When_sessionTopicDeletionFails() {
    Session session = new EasyRandom().nextObject(Session.class);
    when(this.sessionRepository.findByUser(any())).thenReturn(singletonList(session));
    doThrow(new RuntimeException()).when(this.sessionTopicRepository).deleteAllBySessionId(any());
    AskerDeletionWorkflowDTO workflowDTO =
        new AskerDeletionWorkflowDTO(new User(), new ArrayList<>());

    this.deleteAskerRoomsAndSessionsAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(logCaptor.contains(Level.ERROR, "UserService delete workflow error")).isTrue();
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(ASKER));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(DATABASE));
    assertThat(workflowErrors.get(0).getIdentifier(), is(session.getId().toString()));
    assertThat(workflowErrors.get(0).getReason(), is("Unable to delete topics for session"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
  }

  @Test
  void execute_Should_returnExpectedWorkflowError_When_sessionDeletionFails() {
    Session session = new EasyRandom().nextObject(Session.class);
    when(this.sessionRepository.findByUser(any())).thenReturn(singletonList(session));
    doThrow(new RuntimeException()).when(this.sessionRepository).delete(any());
    AskerDeletionWorkflowDTO workflowDTO =
        new AskerDeletionWorkflowDTO(new User(), new ArrayList<>());

    this.deleteAskerRoomsAndSessionsAction.execute(workflowDTO);
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
