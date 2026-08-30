package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAnonymousRegistryIdAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAppointmentServiceAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAskerDraftMessagesAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAskerEventNotificationsAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAskerRoomsAndSessionsAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteDatabaseAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteDatabaseAskerAgencyAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteKeycloakAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteMatrixAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteAppointmentServiceConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteCaseHandoverRequestsForConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteChatAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteConsultantDraftMessagesAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteConsultantEventNotificationsAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteDatabaseConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteDatabaseConsultantAgencyAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteKeycloakConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteMatrixConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Service to trigger deletion of user accounts. */
@Service
@RequiredArgsConstructor
public class DeleteUserAccountService {

  private final @NonNull UserRepository userRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ActionsRegistry actionsRegistry;
  private final @NonNull WorkflowErrorMailService workflowErrorMailService;
  private final @NonNull DeletionLifecycleService deletionLifecycleService;
  private final @NonNull UserHardDeleteClaimService userHardDeleteClaimService;

  /** Deletes all user accounts marked as deleted in database. */
  public void deleteUserAccounts() {
    var workflowErrors = deleteAskersAndCollectPossibleErrors();
    workflowErrors.addAll(deleteConsultantsAndCollectPossibleErrors());

    if (isNotEmpty(workflowErrors)) {
      this.workflowErrorMailService.buildAndSendErrorMail(workflowErrors);
    }
  }

  private List<DeletionWorkflowError> deleteAskersAndCollectPossibleErrors() {
    return this.userRepository.findAllByDeleteDateNotNull().stream()
        .map(User::getUserId)
        .map(userHardDeleteClaimService::claim)
        .flatMap(java.util.Optional::stream)
        .map(this::performClaimedUserDeletion)
        .flatMap(Collection::stream)
        .collect(Collectors.toList());
  }

  private List<DeletionWorkflowError> performClaimedUserDeletion(User user) {
    try {
      return performUserDeletion(user);
    } finally {
      // If the final database action deleted the row this is a no-op. Otherwise the retained row is
      // made retryable after a partial downstream failure.
      userHardDeleteClaimService.release(user.getUserId());
    }
  }

  List<DeletionWorkflowError> performUserDeletion(User user) {

    var deletionWorkflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());

    this.actionsRegistry
        .buildContainerForType(AskerDeletionWorkflowDTO.class)
        .addActionToExecute(DeleteKeycloakAskerAction.class)
        .addActionToExecute(DeleteMatrixAskerAction.class)
        .addActionToExecute(DeleteAskerRoomsAndSessionsAction.class)
        .addActionToExecute(DeleteDatabaseAskerAgencyAction.class)
        .addActionToExecute(DeleteAnonymousRegistryIdAction.class)
        .addActionToExecute(DeleteAppointmentServiceAskerAction.class)
        .addActionToExecute(DeleteAskerDraftMessagesAction.class)
        .addActionToExecute(DeleteAskerEventNotificationsAction.class)
        .addActionToExecute(DeleteDatabaseAskerAction.class)
        .executeActions(deletionWorkflowDTO);

    return deletionWorkflowDTO.getDeletionWorkflowErrors();
  }

  private List<DeletionWorkflowError> deleteConsultantsAndCollectPossibleErrors() {
    return this.consultantRepository.findAllByDeleteDateNotNull().stream()
        .map(deletionLifecycleService::normalizeConsultantLifecycle)
        .peek(consultantRepository::save)
        .filter(deletionLifecycleService::isReadyForHardDelete)
        .map(this::performConsultantDeletion)
        .flatMap(Collection::stream)
        .collect(Collectors.toList());
  }

  public List<DeletionWorkflowError> performConsultantDeletion(Consultant consultant) {

    var deletionWorkflowDTO = new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());

    this.actionsRegistry
        .buildContainerForType(ConsultantDeletionWorkflowDTO.class)
        .addActionToExecute(DeleteKeycloakConsultantAction.class)
        .addActionToExecute(DeleteMatrixConsultantAction.class)
        .addActionToExecute(DeleteDatabaseConsultantAgencyAction.class)
        .addActionToExecute(DeleteChatAction.class)
        .addActionToExecute(DeleteAppointmentServiceConsultantAction.class)
        .addActionToExecute(DeleteCaseHandoverRequestsForConsultantAction.class)
        .addActionToExecute(DeleteConsultantDraftMessagesAction.class)
        .addActionToExecute(DeleteConsultantEventNotificationsAction.class)
        .addActionToExecute(DeleteDatabaseConsultantAction.class)
        .executeActions(deletionWorkflowDTO);

    return deletionWorkflowDTO.getDeletionWorkflowErrors();
  }
}
