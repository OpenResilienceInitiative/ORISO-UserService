package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.*;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Executes already-due inactivity deletion; the lifecycle owner holds the identity claim. */
@Service
@RequiredArgsConstructor
public class InactiveAskerDeletionService {
  private final UserRepository users;
  private final ActionsRegistry actions;

  public List<DeletionWorkflowError> delete(String identityId) {
    var user = users.findById(identityId);
    if (user.isEmpty()) return List.of();
    var outcome = new AskerDeletionWorkflowDTO(user.get(), new ArrayList<>());
    // Stop on the first incomplete system. In particular, keep session room ids after a Matrix
    // failure and keep the identity/account row until every preceding cleanup step is confirmed.
    List<Class<? extends ActionCommand<AskerDeletionWorkflowDTO>>> steps =
        List.of(
            DeleteMatrixAskerAction.class,
            DeleteAskerRoomsAndSessionsAction.class,
            DeleteDatabaseAskerAgencyAction.class,
            DeleteAnonymousRegistryIdAction.class,
            DeleteAppointmentServiceAskerAction.class,
            DeleteAskerDraftMessagesAction.class,
            DeleteAskerEventNotificationsAction.class,
            DeleteKeycloakAskerAction.class,
            DeleteDatabaseAskerAction.class);
    for (var step : steps) {
      actions
          .buildContainerForType(AskerDeletionWorkflowDTO.class)
          .addActionToExecute(step)
          .executeActions(outcome);
      if (!outcome.getDeletionWorkflowErrors().isEmpty()) break;
    }
    return List.copyOf(outcome.getDeletionWorkflowErrors());
  }
}
