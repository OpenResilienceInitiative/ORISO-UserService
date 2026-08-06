package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.MATRIX;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DeleteMatrixAskerActionTest {

  private static final String MATRIX_USER_ID = "@asker:matrix.example";
  private static final String MATRIX_ROOM_ID = "!room:matrix.example";

  @InjectMocks private DeleteMatrixAskerAction deleteMatrixAskerAction;

  @Mock private MatrixSynapseService matrixSynapseService;

  @Mock private SessionRepository sessionRepository;

  @BeforeEach
  public void setup() {
    lenient().when(sessionRepository.findByUser(any(User.class))).thenReturn(emptyList());
  }

  @Test
  public void execute_Should_deactivateMatrixUser_When_userHasMatrixUserId() {
    User user = new User();
    user.setMatrixUserId(MATRIX_USER_ID);
    when(matrixSynapseService.deactivateUser(MATRIX_USER_ID)).thenReturn(true);
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(user, emptyList());

    deleteMatrixAskerAction.execute(workflowDTO);

    verify(matrixSynapseService, times(1)).deactivateUser(MATRIX_USER_ID);
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  @Test
  public void execute_Should_purgeMatrixRoomsBeforeDeactivatingUser_When_sessionsExist() {
    User user = new User();
    user.setMatrixUserId(MATRIX_USER_ID);
    Session session = new Session();
    session.setMatrixRoomId(MATRIX_ROOM_ID);
    when(sessionRepository.findByUser(user)).thenReturn(List.of(session));
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID)).thenReturn(true);
    when(matrixSynapseService.deactivateUser(MATRIX_USER_ID)).thenReturn(true);
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(user, emptyList());

    deleteMatrixAskerAction.execute(workflowDTO);

    var inOrder = org.mockito.Mockito.inOrder(matrixSynapseService);
    inOrder.verify(matrixSynapseService).purgeRoom(MATRIX_ROOM_ID);
    inOrder.verify(matrixSynapseService).deactivateUser(MATRIX_USER_ID);
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  @Test
  public void execute_Should_appendDeletionWorkflowError_When_purgeRoomReturnsFalse() {
    User user = new User();
    user.setMatrixUserId(MATRIX_USER_ID);
    Session session = new Session();
    session.setMatrixRoomId(MATRIX_ROOM_ID);
    when(sessionRepository.findByUser(user)).thenReturn(List.of(session));
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID)).thenReturn(false);
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());

    deleteMatrixAskerAction.execute(workflowDTO);

    verify(matrixSynapseService, times(0)).deactivateUser(anyString());
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(1));
  }

  @Test
  public void execute_Should_notCallMatrixSynapse_When_userHasNoMatrixUserId() {
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(new User(), emptyList());

    deleteMatrixAskerAction.execute(workflowDTO);

    verifyNoInteractions(matrixSynapseService);
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  @Test
  public void execute_Should_appendDeletionWorkflowError_When_deactivateUserReturnsFalse() {
    User user = new User();
    user.setMatrixUserId(MATRIX_USER_ID);
    when(matrixSynapseService.deactivateUser(MATRIX_USER_ID)).thenReturn(false);
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());

    deleteMatrixAskerAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(ASKER));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(MATRIX));
    assertThat(workflowErrors.get(0).getIdentifier(), is(MATRIX_USER_ID));
    assertThat(workflowErrors.get(0).getReason(), is("Unable to delete Matrix user account"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
  }

  @Test
  public void execute_Should_notThrow_When_deactivateUserReturnsFalse() {
    User user = new User();
    user.setMatrixUserId(MATRIX_USER_ID);
    when(matrixSynapseService.deactivateUser(anyString())).thenReturn(false);
    AskerDeletionWorkflowDTO workflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());

    assertDoesNotThrow(() -> deleteMatrixAskerAction.execute(workflowDTO));
  }
}
