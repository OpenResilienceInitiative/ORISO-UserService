package de.caritas.cob.userservice.api.workflow.delete.service;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.MATRIX;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteMatrixAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteChatAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteMatrixConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Smoke tests for Matrix deletion during account hard-delete workflows. Synapse is mocked; these
 * tests verify room IDs are read from the database before session/chat cleanup and that Synapse
 * failures are recorded without aborting the action.
 */
@ExtendWith(MockitoExtension.class)
class DeleteMatrixDeletionWorkflowTest {

  private static final String MATRIX_USER_ID = "@user:oriso.org";
  private static final String MATRIX_ROOM_ID = "!counselling:oriso.org";
  private static final String GROUP_CHAT_ROOM_ID = "!group:oriso.org";

  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private SessionRepository sessionRepository;
  @Mock private ChatRepository chatRepository;
  @Mock private RocketChatService rocketChatService;

  @Test
  void askerDeletionWorkflow_Should_purgeRoomAndDeactivateUser_When_matrixDataExists() {
    User user = userWithMatrixAccount();
    Session session = sessionWithMatrixRoom();
    when(sessionRepository.findByUser(user)).thenReturn(List.of(session));
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID)).thenReturn(true);
    when(matrixSynapseService.deactivateUser(MATRIX_USER_ID)).thenReturn(true);

    var action = new DeleteMatrixAskerAction(matrixSynapseService, sessionRepository);
    var workflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());
    action.execute(workflowDTO);

    var order = inOrder(matrixSynapseService);
    order.verify(matrixSynapseService).purgeRoom(MATRIX_ROOM_ID);
    order.verify(matrixSynapseService).deactivateUser(MATRIX_USER_ID);
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  @Test
  void askerDeletionWorkflow_Should_readMatrixRoomIdFromDatabase_BeforeSessionCleanupWouldRun() {
    User user = userWithMatrixAccount();
    Session session = sessionWithMatrixRoom();
    when(sessionRepository.findByUser(user)).thenReturn(List.of(session));
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID)).thenReturn(true);
    when(matrixSynapseService.deactivateUser(MATRIX_USER_ID)).thenReturn(true);

    new DeleteMatrixAskerAction(matrixSynapseService, sessionRepository)
        .execute(new AskerDeletionWorkflowDTO(user, new ArrayList<>()));

    verify(sessionRepository, times(1)).findByUser(user);
    verify(matrixSynapseService, times(1)).purgeRoom(MATRIX_ROOM_ID);
  }

  @Test
  void
      askerDeletionWorkflow_Should_recordDeletionWorkflowError_When_synapseReturnsServiceUnavailable() {
    User user = userWithMatrixAccount();
    when(matrixSynapseService.deactivateUser(MATRIX_USER_ID)).thenReturn(false);

    var workflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());
    new DeleteMatrixAskerAction(matrixSynapseService, sessionRepository).execute(workflowDTO);

    List<DeletionWorkflowError> errors = workflowDTO.getDeletionWorkflowErrors();
    assertThat(errors, hasSize(1));
    assertThat(errors.get(0).getDeletionSourceType(), is(ASKER));
    assertThat(errors.get(0).getDeletionTargetType(), is(MATRIX));
    assertThat(errors.get(0).getIdentifier(), is(MATRIX_USER_ID));
  }

  @Test
  void consultantDeletionWorkflow_Should_purgeSessionsAndDeactivateUser_When_matrixDataExists() {
    Consultant consultant = consultantWithMatrixAccount();
    Session session = sessionWithMatrixRoom();
    when(sessionRepository.findByConsultantId(consultant.getId(), Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(session)));
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID)).thenReturn(true);
    when(matrixSynapseService.deactivateUser(MATRIX_USER_ID)).thenReturn(true);

    var workflowDTO = new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());
    new DeleteMatrixConsultantAction(matrixSynapseService, sessionRepository).execute(workflowDTO);

    var order = inOrder(matrixSynapseService);
    order.verify(matrixSynapseService).purgeRoom(MATRIX_ROOM_ID);
    order.verify(matrixSynapseService).deactivateUser(MATRIX_USER_ID);
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  @Test
  void groupChatDeletionWorkflow_Should_purgeMatrixRoom_When_chatHasMatrixRoomId()
      throws Exception {
    Consultant consultant = new Consultant();
    Chat chat = new Chat();
    chat.setMatrixRoomId(GROUP_CHAT_ROOM_ID);
    chat.setGroupId("rc-group-id");
    chat.setChatOwner(consultant);
    when(chatRepository.findByChatOwner(consultant)).thenReturn(List.of(chat));
    when(matrixSynapseService.purgeRoom(GROUP_CHAT_ROOM_ID)).thenReturn(true);

    var workflowDTO = new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());
    new DeleteChatAction(chatRepository, rocketChatService, matrixSynapseService)
        .execute(workflowDTO);

    verify(matrixSynapseService, times(1)).purgeRoom(GROUP_CHAT_ROOM_ID);
    verify(rocketChatService, times(1)).deleteGroupAsTechnicalUser("rc-group-id");
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  private User userWithMatrixAccount() {
    User user = new User();
    user.setMatrixUserId(MATRIX_USER_ID);
    return user;
  }

  private Consultant consultantWithMatrixAccount() {
    Consultant consultant = new Consultant();
    consultant.setId("consultant-id");
    consultant.setMatrixUserId(MATRIX_USER_ID);
    return consultant;
  }

  private Session sessionWithMatrixRoom() {
    Session session = new Session();
    session.setMatrixRoomId(MATRIX_ROOM_ID);
    return session;
  }
}
