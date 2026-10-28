package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAnonymousRegistryIdAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAppointmentServiceAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAskerDraftMessagesAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAskerEventNotificationsAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAskerReplyEmailDeliveriesAction;
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
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteConsultantMessageEmailDeliveriesAction;
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

  private DeleteDatabaseAskerAction acknowledgedFinalAction;
  private DeleteAppointmentServiceAskerAction absentAppointments;
  private DeleteAskerDraftMessagesAction absentDrafts;
  private DeleteAskerEventNotificationsAction absentNotifications;

  /** Existing action beans only; the old six-argument compatibility constructor remains. */
  @org.springframework.beans.factory.annotation.Autowired
  public DeleteUserAccountService(
      UserRepository users,
      ConsultantRepository consultants,
      ActionsRegistry registry,
      WorkflowErrorMailService errors,
      DeletionLifecycleService lifecycle,
      UserHardDeleteClaimService hardDelete,
      DeleteDatabaseAskerAction finalAction,
      DeleteAppointmentServiceAskerAction appointments,
      DeleteAskerDraftMessagesAction drafts,
      DeleteAskerEventNotificationsAction notifications) {
    this(users, consultants, registry, errors, lifecycle, hardDelete);
    this.acknowledgedFinalAction = finalAction;
    this.absentAppointments = appointments;
    this.absentDrafts = drafts;
    this.absentNotifications = notifications;
  }

  /** Continues only an acknowledgement issued by the held scheduled-deletion owner. */
  public void continueAcknowledgedDeletion(
      ScheduledAskerMaintenance.AcknowledgedDeletion acknowledged) {
    java.util.Objects.requireNonNull(acknowledged);
    acknowledged.requireCurrent();
    if (acknowledged.completed()) return;
    if (acknowledged.inactivityCause()) {
      if (acknowledgedFinalAction == null
          || absentAppointments == null
          || absentDrafts == null
          || absentNotifications == null) throw new ScopedIdentityCommandRequiredException();
      TenantContext.runIn(
          acknowledged.tenantId(),
          () -> {
            if (acknowledged.hasLocalUser()) continueInactiveUser(acknowledged);
            else continueInactiveAbsence(acknowledged);
          });
      return;
    }
    if (!acknowledged.hasLocalUser()) throw new ScopedIdentityCommandRequiredException();
    TenantContext.runIn(
        acknowledged.tenantId(),
        () -> {
          var original = new java.util.concurrent.atomic.AtomicReference<User>();
          acknowledged.runOwnedLocal(
              () -> {
                var loaded = userRepository.findById(acknowledged.accountId()).orElseThrow();
                acknowledged.requireCurrentUser(loaded);
                original.set(loaded);
              });
          var target =
              new AskerDeletionWorkflowDTO(original.get(), new ArrayList<>(), acknowledged);
          executeAcknowledged(target, DeleteMatrixAskerAction.class);
          executeAcknowledged(target, DeleteAskerRoomsAndSessionsAction.class);
          target.runOwnedLocal(
              () -> executeAcknowledged(target, DeleteDatabaseAskerAgencyAction.class));
          target.runOwnedLocal(
              () -> executeAcknowledged(target, DeleteAnonymousRegistryIdAction.class));
          target.runOwnedExternal(
              () -> executeAcknowledged(target, DeleteAppointmentServiceAskerAction.class));
          target.runOwnedLocal(
              () -> executeAcknowledged(target, DeleteAskerDraftMessagesAction.class));
          target.runOwnedLocal(
              () -> executeAcknowledged(target, DeleteAskerEventNotificationsAction.class));
          target.runOwnedLocal(
              () -> executeAcknowledged(target, DeleteAskerReplyEmailDeliveriesAction.class));
          target.completeOwnedLocalDeletion(
              () -> executeAcknowledged(target, DeleteDatabaseAskerAction.class));
          if (isNotEmpty(target.getDeletionWorkflowErrors())) {
            acknowledged.requireCurrent();
            workflowErrorMailService.buildAndSendErrorMail(target.getDeletionWorkflowErrors());
            acknowledged.requireCurrent();
          }
        });
  }

  /** Continues the existing nine Consultant steps only from the held owner's strict native ACK. */
  public void continueAcknowledgedConsultantDeletion(
      ScheduledConsultantMaintenance.AcknowledgedConsultantDeletion acknowledged) {
    java.util.Objects.requireNonNull(acknowledged);
    acknowledged.requireCurrent();
    if (acknowledged.completed()) return;
    TenantContext.runIn(
        acknowledged.tenantId(),
        () -> {
          var original = new java.util.concurrent.atomic.AtomicReference<Consultant>();
          acknowledged.runOwnedLocal(
              () -> {
                var loaded = consultantRepository.findById(acknowledged.accountId()).orElseThrow();
                acknowledged.requireCurrentConsultant(loaded);
                original.set(loaded);
              });
          var target =
              new ConsultantDeletionWorkflowDTO(original.get(), new ArrayList<>(), acknowledged);
          executeAcknowledgedConsultant(target, DeleteMatrixConsultantAction.class);
          target.runOwnedLocal(
              () ->
                  executeAcknowledgedConsultant(
                      target, DeleteDatabaseConsultantAgencyAction.class));
          executeAcknowledgedConsultant(target, DeleteChatAction.class);
          target.runOwnedExternal(
              () ->
                  executeAcknowledgedConsultant(
                      target, DeleteAppointmentServiceConsultantAction.class));
          target.runOwnedLocal(
              () ->
                  executeAcknowledgedConsultant(
                      target, DeleteCaseHandoverRequestsForConsultantAction.class));
          target.runOwnedLocal(
              () ->
                  executeAcknowledgedConsultant(target, DeleteConsultantDraftMessagesAction.class));
          target.runOwnedLocal(
              () ->
                  executeAcknowledgedConsultant(
                      target, DeleteConsultantEventNotificationsAction.class));
          target.runOwnedLocal(
              () ->
                  executeAcknowledgedConsultant(
                      target, DeleteConsultantMessageEmailDeliveriesAction.class));
          target.completeOwnedLocalDeletion(
              () -> executeAcknowledgedConsultant(target, DeleteDatabaseConsultantAction.class));
          if (isNotEmpty(target.getDeletionWorkflowErrors())) {
            acknowledged.requireCurrent();
            workflowErrorMailService.buildAndSendErrorMail(target.getDeletionWorkflowErrors());
            acknowledged.requireCurrent();
          }
        });
  }

  private void executeAcknowledgedConsultant(
      ConsultantDeletionWorkflowDTO target,
      Class<? extends ActionCommand<ConsultantDeletionWorkflowDTO>> action) {
    actionsRegistry
        .buildContainerForType(ConsultantDeletionWorkflowDTO.class)
        .addActionToExecute(action)
        .executeActions(target);
  }

  private void continueInactiveUser(ScheduledAskerMaintenance.AcknowledgedDeletion acknowledged) {
    var original = new java.util.concurrent.atomic.AtomicReference<User>();
    acknowledged.runOwnedLocal(
        () -> {
          var loaded = userRepository.findById(acknowledged.accountId()).orElseThrow();
          acknowledged.requireCurrentUser(loaded);
          original.set(loaded);
        });
    var target = new AskerDeletionWorkflowDTO(original.get(), new ArrayList<>(), acknowledged);
    executeAcknowledged(target, DeleteMatrixAskerAction.class);
    if (isNotEmpty(target.getDeletionWorkflowErrors())) return;
    executeAcknowledged(target, DeleteAskerRoomsAndSessionsAction.class);
    if (isNotEmpty(target.getDeletionWorkflowErrors())) return;
    target.runOwnedLocal(() -> executeAcknowledged(target, DeleteDatabaseAskerAgencyAction.class));
    if (isNotEmpty(target.getDeletionWorkflowErrors())) return;
    target.runOwnedLocal(() -> executeAcknowledged(target, DeleteAnonymousRegistryIdAction.class));
    if (isNotEmpty(target.getDeletionWorkflowErrors())) return;
    target.runOwnedExternal(
        () -> executeAcknowledged(target, DeleteAppointmentServiceAskerAction.class));
    if (isNotEmpty(target.getDeletionWorkflowErrors())) return;
    target.runOwnedLocal(() -> executeAcknowledged(target, DeleteAskerDraftMessagesAction.class));
    if (isNotEmpty(target.getDeletionWorkflowErrors())) return;
    target.runOwnedLocal(
        () -> executeAcknowledged(target, DeleteAskerEventNotificationsAction.class));
    if (isNotEmpty(target.getDeletionWorkflowErrors())) return;
    var prepared = acknowledgedFinalAction.prepareAcknowledged(target);
    if (prepared.isEmpty()) return;
    acknowledged.beforeFinalLocalDeletion(prepared.orElseThrow());
    acknowledgedFinalAction.completeAcknowledged(prepared.orElseThrow());
  }

  private void continueInactiveAbsence(
      ScheduledAskerMaintenance.AcknowledgedDeletion acknowledged) {
    if (isNotEmpty(absentAppointments.executeOriginallyAbsent(acknowledged))) return;
    if (isNotEmpty(absentDrafts.executeOriginallyAbsent(acknowledged))) return;
    if (isNotEmpty(absentNotifications.executeOriginallyAbsent(acknowledged))) return;
    acknowledged.beforeFinalLocalAbsence();
    acknowledged.completeOwnedLocalDeletion(() -> {});
  }

  private void executeAcknowledged(
      AskerDeletionWorkflowDTO target,
      Class<? extends ActionCommand<AskerDeletionWorkflowDTO>> action) {
    actionsRegistry
        .buildContainerForType(AskerDeletionWorkflowDTO.class)
        .addActionToExecute(action)
        .executeActions(target);
  }

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
      // If the final database action deleted the row this is a no-op. Otherwise at least one
      // irreversible step may already have completed, so the retained row is retryable for hard
      // deletion but can never become reactivation-eligible again.
      userHardDeleteClaimService.release(user.getUserId());
    }
  }

  List<DeletionWorkflowError> performUserDeletion(User user) {

    var deletionWorkflowDTO = new AskerDeletionWorkflowDTO(user, new ArrayList<>());

    try {
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
          .addActionToExecute(DeleteAskerReplyEmailDeliveriesAction.class)
          .addActionToExecute(DeleteDatabaseAskerAction.class)
          .executeActions(deletionWorkflowDTO);
    } catch (ScopedIdentityCommandRequiredException denied) {
      // The native action has recorded the refusal. Stop before any downstream effect.
    }

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

    try {
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
          .addActionToExecute(DeleteConsultantMessageEmailDeliveriesAction.class)
          .addActionToExecute(DeleteDatabaseConsultantAction.class)
          .executeActions(deletionWorkflowDTO);
    } catch (ScopedIdentityCommandRequiredException denied) {
      // The native action recorded the refusal. Keep the consultant and stop downstream effects.
    }

    return deletionWorkflowDTO.getDeletionWorkflowErrors();
  }

  /** Verify the same finite action containers used by acknowledged cleanup, without executing. */
  public boolean hasScopedAccountBindings() {
    if (acknowledgedFinalAction == null
        || absentAppointments == null
        || absentDrafts == null
        || absentNotifications == null) return false;
    actionsRegistry
        .buildContainerForType(AskerDeletionWorkflowDTO.class)
        .addActionToExecute(DeleteMatrixAskerAction.class)
        .addActionToExecute(DeleteAskerRoomsAndSessionsAction.class)
        .addActionToExecute(DeleteDatabaseAskerAgencyAction.class)
        .addActionToExecute(DeleteAnonymousRegistryIdAction.class)
        .addActionToExecute(DeleteAppointmentServiceAskerAction.class)
        .addActionToExecute(DeleteAskerDraftMessagesAction.class)
        .addActionToExecute(DeleteAskerEventNotificationsAction.class)
        .addActionToExecute(DeleteAskerReplyEmailDeliveriesAction.class)
        .addActionToExecute(DeleteDatabaseAskerAction.class);
    actionsRegistry
        .buildContainerForType(ConsultantDeletionWorkflowDTO.class)
        .addActionToExecute(DeleteMatrixConsultantAction.class)
        .addActionToExecute(DeleteDatabaseConsultantAgencyAction.class)
        .addActionToExecute(DeleteChatAction.class)
        .addActionToExecute(DeleteAppointmentServiceConsultantAction.class)
        .addActionToExecute(DeleteCaseHandoverRequestsForConsultantAction.class)
        .addActionToExecute(DeleteConsultantDraftMessagesAction.class)
        .addActionToExecute(DeleteConsultantEventNotificationsAction.class)
        .addActionToExecute(DeleteConsultantMessageEmailDeliveriesAction.class)
        .addActionToExecute(DeleteDatabaseConsultantAction.class);
    return true;
  }
}
