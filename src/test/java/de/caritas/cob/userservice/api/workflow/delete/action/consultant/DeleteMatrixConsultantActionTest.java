package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.MATRIX;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
public class DeleteMatrixConsultantActionTest {

  private static final String MATRIX_USER_ID = "@consultant:matrix.example";
  private static final String MATRIX_ROOM_ID = "!room:matrix.example";
  private static final String CONSULTANT_ID = "consultant-id";

  @InjectMocks private DeleteMatrixConsultantAction deleteMatrixConsultantAction;

  @Mock private MatrixSynapseService matrixSynapseService;

  @Mock private SessionRepository sessionRepository;

  @Mock private de.caritas.cob.userservice.api.port.out.ConsultantRepository consultantRepository;

  @BeforeEach
  public void setup() {
    lenient()
        .when(sessionRepository.findByConsultantId(any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(emptyList()));
  }

  @Test
  public void execute_Should_deactivateMatrixUser_When_consultantHasMatrixUserId() {
    Consultant consultant = consultantWithMatrixUserId();
    when(matrixSynapseService.deactivateUser(MATRIX_USER_ID)).thenReturn(true);
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, emptyList());

    deleteMatrixConsultantAction.execute(workflowDTO);

    verify(matrixSynapseService, times(1)).deactivateUser(MATRIX_USER_ID);
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  @Test
  public void execute_Should_purgeMatrixRoomsBeforeDeactivatingUser_When_sessionsExist() {
    Consultant consultant = consultantWithMatrixUserId();
    Session session = new Session();
    session.setMatrixRoomId(MATRIX_ROOM_ID);
    when(sessionRepository.findByConsultantId(eq(CONSULTANT_ID), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(session)));
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID)).thenReturn(true);
    when(matrixSynapseService.deactivateUser(MATRIX_USER_ID)).thenReturn(true);
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, emptyList());

    deleteMatrixConsultantAction.execute(workflowDTO);

    var inOrder = org.mockito.Mockito.inOrder(matrixSynapseService);
    inOrder.verify(matrixSynapseService).purgeRoom(MATRIX_ROOM_ID);
    inOrder.verify(matrixSynapseService).deactivateUser(MATRIX_USER_ID);
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  @Test
  public void execute_Should_appendDeletionWorkflowError_When_purgeRoomReturnsFalse() {
    Consultant consultant = consultantWithMatrixUserId();
    Session session = new Session();
    session.setMatrixRoomId(MATRIX_ROOM_ID);
    when(sessionRepository.findByConsultantId(eq(CONSULTANT_ID), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(session)));
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID)).thenReturn(false);
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());

    deleteMatrixConsultantAction.execute(workflowDTO);

    verify(matrixSynapseService, times(0)).deactivateUser(anyString());
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(1));
  }

  @Test
  public void execute_Should_notCallMatrixSynapse_When_consultantHasNoMatrixUserId() {
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(new Consultant(), emptyList());

    deleteMatrixConsultantAction.execute(workflowDTO);

    verifyNoInteractions(matrixSynapseService);
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  @Test
  public void execute_Should_appendDeletionWorkflowError_When_deactivateUserReturnsFalse() {
    Consultant consultant = consultantWithMatrixUserId();
    when(matrixSynapseService.deactivateUser(MATRIX_USER_ID)).thenReturn(false);
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());

    deleteMatrixConsultantAction.execute(workflowDTO);
    List<DeletionWorkflowError> workflowErrors = workflowDTO.getDeletionWorkflowErrors();

    assertThat(workflowErrors, hasSize(1));
    assertThat(workflowErrors.get(0).getDeletionSourceType(), is(CONSULTANT));
    assertThat(workflowErrors.get(0).getDeletionTargetType(), is(MATRIX));
    assertThat(workflowErrors.get(0).getIdentifier(), is(MATRIX_USER_ID));
    assertThat(workflowErrors.get(0).getReason(), is("Unable to delete Matrix user account"));
    assertThat(workflowErrors.get(0).getTimestamp(), notNullValue());
  }

  @Test
  public void execute_Should_notThrow_When_deactivateUserReturnsFalse() {
    Consultant consultant = consultantWithMatrixUserId();
    when(matrixSynapseService.deactivateUser(anyString())).thenReturn(false);
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());

    assertDoesNotThrow(() -> deleteMatrixConsultantAction.execute(workflowDTO));
  }

  private Consultant consultantWithMatrixUserId() {
    Consultant consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setMatrixUserId(MATRIX_USER_ID);
    return consultant;
  }

  /**
   * #1489: the old row survived a failed run, its username was released and a new counsellor took
   * the name, which reactivated this Matrix account for them. A retry must leave it alone.
   */
  @Test
  public void execute_Should_notDeactivateMatrixUser_When_anActiveConsultantNowHoldsIt() {
    Consultant consultant = consultantWithMatrixUserId();
    Consultant successor = new Consultant();
    successor.setId("successor-id");
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(MATRIX_USER_ID))
        .thenReturn(java.util.Optional.of(successor));
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, emptyList());

    deleteMatrixConsultantAction.execute(workflowDTO);

    verify(matrixSynapseService, org.mockito.Mockito.never()).deactivateUser(any());
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  /**
   * #1489: the name was released in an earlier run, after this account was deactivated. The new
   * holder may be in a cancellable deletion of their own (delete date set), so the lookup for an
   * active holder would not protect them; the released name does.
   */
  @Test
  public void execute_Should_notDeactivateMatrixUser_When_theUsernameWasAlreadyReleased() {
    Consultant consultant = consultantWithMatrixUserId();
    consultant.setUsername("deleted:" + CONSULTANT_ID);
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(consultant, emptyList());

    deleteMatrixConsultantAction.execute(workflowDTO);

    verify(matrixSynapseService, org.mockito.Mockito.never()).deactivateUser(any());
    assertThat(workflowDTO.getDeletionWorkflowErrors(), hasSize(0));
  }

  /** A run that outlived its claim holds a stale copy; another run released the name meanwhile. */
  @Test
  public void execute_Should_notDeactivateMatrixUser_When_anotherRunReleasedTheUsernameMeanwhile() {
    Consultant stale = consultantWithMatrixUserId();
    Consultant current = consultantWithMatrixUserId();
    current.setUsername("deleted:" + CONSULTANT_ID);
    when(consultantRepository.findById(CONSULTANT_ID)).thenReturn(java.util.Optional.of(current));
    ConsultantDeletionWorkflowDTO workflowDTO =
        new ConsultantDeletionWorkflowDTO(stale, emptyList());

    deleteMatrixConsultantAction.execute(workflowDTO);

    verify(matrixSynapseService, org.mockito.Mockito.never()).deactivateUser(any());
  }
}
