package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.actions.registry.ActionContainer;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteCaseHandoverRequestsForConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteConsultantDraftMessagesAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteConsultantEventNotificationsAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteConsultantMessageEmailDeliveriesAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteDatabaseConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteDatabaseConsultantAgencyAction;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;

/** Local action order only; callers retain authorization, remote effects and execution. */
public final class ConsultantLocalCleanupActions {
  private ConsultantLocalCleanupActions() {}

  public static ActionContainer<ConsultantDeletionWorkflowDTO> addAllTo(
      ActionContainer<ConsultantDeletionWorkflowDTO> actions) {
    return addAccountArtifactsTo(addAgencyRelationsTo(actions));
  }

  public static ActionContainer<ConsultantDeletionWorkflowDTO> addAgencyRelationsTo(
      ActionContainer<ConsultantDeletionWorkflowDTO> actions) {
    return actions.addActionToExecute(DeleteDatabaseConsultantAgencyAction.class);
  }

  public static ActionContainer<ConsultantDeletionWorkflowDTO> addAccountArtifactsTo(
      ActionContainer<ConsultantDeletionWorkflowDTO> actions) {
    return actions
        .addActionToExecute(DeleteCaseHandoverRequestsForConsultantAction.class)
        .addActionToExecute(DeleteConsultantDraftMessagesAction.class)
        .addActionToExecute(DeleteConsultantEventNotificationsAction.class)
        .addActionToExecute(DeleteConsultantMessageEmailDeliveriesAction.class)
        .addActionToExecute(DeleteDatabaseConsultantAction.class);
  }
}
