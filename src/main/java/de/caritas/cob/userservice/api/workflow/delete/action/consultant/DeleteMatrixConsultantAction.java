package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
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
  private final @NonNull ConsultantRepository consultantRepository;

  public DeleteMatrixConsultantAction(
      @NonNull MatrixSynapseService matrixSynapseService,
      @NonNull SessionRepository sessionRepository,
      @NonNull ConsultantRepository consultantRepository) {
    super(matrixSynapseService);
    this.sessionRepository = sessionRepository;
    this.consultantRepository = consultantRepository;
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
      if (DeleteDatabaseConsultantAction.hasReleasedUsername(consultant)) {
        // An earlier run deactivated this account and released the name. A new counsellor may have
        // taken the name and reactivated the account since, and they may themselves be in a
        // cancellable deletion, so this row never touches the account again (#1489).
        return;
      }
      if (isReassignedToAnotherConsultant(consultant)) {
        // The row survived an earlier failed run, its username was released, and a new counsellor
        // took the name: creation reactivated this same Matrix account for them (#1489). It is no
        // longer the deleted counsellor's account, so a retry must not deactivate it.
        return;
      }
      deactivateMatrixUser(consultant.getMatrixUserId());
    } catch (Exception e) {
      appendErrorsForSourceType(
          actionTarget.getDeletionWorkflowErrors(),
          CONSULTANT,
          actionTarget.getConsultant().getMatrixUserId(),
          e);
    }
  }

  private boolean isReassignedToAnotherConsultant(Consultant consultant) {
    var matrixUserId = consultant.getMatrixUserId();
    if (matrixUserId == null || matrixUserId.isBlank()) {
      return false;
    }
    return TenantContext.supplyAcrossTenants(
            () -> consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(matrixUserId))
        .filter(active -> !active.getId().equals(consultant.getId()))
        .isPresent();
  }
}
