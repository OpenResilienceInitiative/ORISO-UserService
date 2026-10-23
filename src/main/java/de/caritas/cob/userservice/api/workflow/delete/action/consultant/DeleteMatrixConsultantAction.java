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
    if (actionTarget.isAcknowledged()) {
      var sessions = new java.util.concurrent.atomic.AtomicReference<java.util.List<de.caritas.cob.userservice.api.model.Session>>();
      var incomplete = new java.util.concurrent.atomic.AtomicBoolean();
      actionTarget.runOwnedLocal(() -> {
        try {
          sessions.set(sessionRepository.findByConsultantId(actionTarget.getConsultant().getId(), Pageable.unpaged()).getContent());
        } catch (Exception failure) {
          incomplete.set(true);
          appendErrorsForSourceType(actionTarget.getDeletionWorkflowErrors(), CONSULTANT,
              actionTarget.getConsultant().getMatrixUserId(), failure);
        }
      });
      if (incomplete.get()) return;
      for (var session : sessions.get()) {
        actionTarget.runOwnedExternal(() -> {
          try { purgeMatrixRoom(session.getMatrixRoomId()); }
          catch (Exception failure) {
            incomplete.set(true);
            appendErrorsForSourceType(actionTarget.getDeletionWorkflowErrors(), CONSULTANT,
                actionTarget.getConsultant().getMatrixUserId(), failure);
          }
        });
        if (incomplete.get()) return;
      }
      actionTarget.runOwnedExternal(() -> {
        try { deactivateMatrixUser(actionTarget.getConsultant().getMatrixUserId()); }
        catch (Exception failure) {
          appendErrorsForSourceType(actionTarget.getDeletionWorkflowErrors(), CONSULTANT,
              actionTarget.getConsultant().getMatrixUserId(), failure);
        }
      });
      return;
    }
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
