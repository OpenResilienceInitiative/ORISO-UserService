package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteAppointmentServiceAskerAction
    implements ActionCommand<AskerDeletionWorkflowDTO> {

  private final @NonNull AppointmentService appointmentService;

  /** Only the actual original-absence inactivity acknowledgement can name this identity. */
  public java.util.List<DeletionWorkflowError> executeOriginallyAbsent(
      de.caritas.cob.userservice.api.workflow.delete.service.ScheduledAskerMaintenance.AcknowledgedDeletion acknowledged) {
    java.util.Objects.requireNonNull(acknowledged);
    if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    acknowledged.requireCurrent();
    if (!acknowledged.inactivityCause() || acknowledged.hasLocalUser())
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    String account = acknowledged.accountId();
    var errors = new java.util.ArrayList<DeletionWorkflowError>();
      try { this.appointmentService.deleteAsker(account); }
      catch(Exception unavailable) {
        errors.add(DeletionWorkflowError.builder().deletionSourceType(ASKER)
            .deletionTargetType(DeletionTargetType.APPOINTMENT_SERVICE).identifier(account)
            .reason("Unable to delete asker").timestamp(nowInUtc()).build());
      }
    acknowledged.requireCurrent();
    return java.util.List.copyOf(errors);
  }

  @Override
  public void execute(AskerDeletionWorkflowDTO actionTarget) {
    try {
      this.appointmentService.deleteAsker(actionTarget.getUser().getUserId());
    } catch (Exception e) {
      log.error("Appointment service delete workflow error: ", e);
      actionTarget
          .getDeletionWorkflowErrors()
          .add(
              DeletionWorkflowError.builder()
                  .deletionSourceType(ASKER)
                  .deletionTargetType(DeletionTargetType.APPOINTMENT_SERVICE)
                  .identifier(actionTarget.getUser().getUserId())
                  .reason("Unable to delete asker")
                  .timestamp(nowInUtc())
                  .build());
    }
  }
}
