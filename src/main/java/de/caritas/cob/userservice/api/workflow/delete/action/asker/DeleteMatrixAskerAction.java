package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.workflow.delete.action.DeleteMatrixUserAction;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import lombok.NonNull;
import org.springframework.stereotype.Component;

/** Action to delete a Matrix user account and associated counselling rooms. */
@Component
public class DeleteMatrixAskerAction extends DeleteMatrixUserAction
    implements ActionCommand<AskerDeletionWorkflowDTO> {

  private final @NonNull SessionRepository sessionRepository;

  public DeleteMatrixAskerAction(
      @NonNull MatrixSynapseService matrixSynapseService,
      @NonNull SessionRepository sessionRepository) {
    super(matrixSynapseService);
    this.sessionRepository = sessionRepository;
  }

  /**
   * Deletes the given {@link AskerDeletionWorkflowDTO}s user related Matrix account and purges
   * associated Matrix rooms.
   *
   * @param actionTarget the {@link AskerDeletionWorkflowDTO}
   */
  @Override
  public void execute(AskerDeletionWorkflowDTO actionTarget) {
    if (actionTarget.isAcknowledged()) {
      var sessions =
          new java.util.concurrent.atomic.AtomicReference<
              java.util.List<de.caritas.cob.userservice.api.model.Session>>();
      actionTarget.runOwnedLocal(
          () -> sessions.set(sessionRepository.findByUser(actionTarget.getUser())));
      var incomplete = new java.util.concurrent.atomic.AtomicBoolean();
      for (var session : sessions.get()) {
        actionTarget.runOwnedExternal(
            () -> {
              try {
                purgeMatrixRoom(session.getMatrixRoomId());
              } catch (Exception failure) {
                incomplete.set(true);
                appendErrorsForSourceType(
                    actionTarget.getDeletionWorkflowErrors(),
                    ASKER,
                    actionTarget.getUser().getMatrixUserId(),
                    failure);
              }
            });
        if (incomplete.get()) return;
      }
      actionTarget.runOwnedExternal(
          () -> {
            try {
              deactivateMatrixUser(actionTarget.getUser().getMatrixUserId());
            } catch (Exception failure) {
              appendErrorsForSourceType(
                  actionTarget.getDeletionWorkflowErrors(),
                  ASKER,
                  actionTarget.getUser().getMatrixUserId(),
                  failure);
            }
          });
      return;
    }
    try {
      User user = actionTarget.getUser();
      sessionRepository
          .findByUser(user)
          .forEach(session -> purgeMatrixRoom(session.getMatrixRoomId()));
      deactivateMatrixUser(user.getMatrixUserId());
    } catch (Exception e) {
      appendErrorsForSourceType(
          actionTarget.getDeletionWorkflowErrors(),
          ASKER,
          actionTarget.getUser().getMatrixUserId(),
          e);
    }
  }
}
