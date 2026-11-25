package de.caritas.cob.userservice.api.workflow.delete.service;

import static java.util.Objects.isNull;

import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteSingleRoomAndSessionAction;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.model.SessionDeletionWorkflowDTO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Service to trigger deletion of user sessions. */
@Service
@RequiredArgsConstructor
public class DeleteSessionService {

  private final @NonNull ActionsRegistry actionsRegistry;

  /**
   * Deletes the given session and schedules cleanup of its Matrix room.
   *
   * @param session the {@link Session} to delete
   * @return a {@link List} of {@link DeletionWorkflowError}
   */
  public List<DeletionWorkflowError> performSessionDeletion(Session session) {
    return performSessionDeletion(session, false);
  }

  /**
   * As {@link #performSessionDeletion(Session)}, but a failed child cleanup step keeps the session
   * row, so a run that selects it again can retry (ORISO-Admin#1524).
   */
  public List<DeletionWorkflowError> performSessionDeletionKeepingFailed(Session session) {
    return performSessionDeletion(session, true);
  }

  private List<DeletionWorkflowError> performSessionDeletion(
      Session session, boolean keepSessionOnChildFailure) {

    if (isNull(session)) {
      return Collections.emptyList();
    }

    var deletionWorkflowDTO =
        new SessionDeletionWorkflowDTO(session, new ArrayList<>(), keepSessionOnChildFailure);

    this.actionsRegistry
        .buildContainerForType(SessionDeletionWorkflowDTO.class)
        .addActionToExecute(DeleteSingleRoomAndSessionAction.class)
        .executeActions(deletionWorkflowDTO);

    return deletionWorkflowDTO.getDeletionWorkflowErrors();
  }
}
