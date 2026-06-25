package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.workflow.delete.action.DeleteMatrixUserAction;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import lombok.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/** Action to delete a Matrix user account and associated counselling rooms. */
@Component
public class DeleteMatrixConsultantAction extends DeleteMatrixUserAction
    implements ActionCommand<ConsultantDeletionWorkflowDTO> {

  private final @NonNull SessionRepository sessionRepository;

  public DeleteMatrixConsultantAction(
      @NonNull MatrixSynapseService matrixSynapseService,
      @NonNull SessionRepository sessionRepository) {
    super(matrixSynapseService);
    this.sessionRepository = sessionRepository;
  }

  /**
   * Deletes the given {@link ConsultantDeletionWorkflowDTO}s consultant related Matrix account and
   * purges associated Matrix rooms.
   *
   * @param actionTarget the {@link ConsultantDeletionWorkflowDTO}
   */
  @Override
  public void execute(ConsultantDeletionWorkflowDTO actionTarget) {
    try {
      Consultant consultant = actionTarget.getConsultant();
      sessionRepository
          .findByConsultantId(consultant.getId(), Pageable.unpaged())
          .forEach(session -> purgeMatrixRoom(session.getMatrixRoomId()));
      deactivateMatrixUser(consultant.getMatrixUserId());
    } catch (Exception e) {
      appendErrorsForSourceType(
          actionTarget.getDeletionWorkflowErrors(),
          CONSULTANT,
          actionTarget.getConsultant().getMatrixUserId(),
          e);
    }
  }
}
