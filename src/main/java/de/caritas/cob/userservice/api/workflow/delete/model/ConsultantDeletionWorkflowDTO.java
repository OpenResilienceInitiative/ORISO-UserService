package de.caritas.cob.userservice.api.workflow.delete.model;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
import de.caritas.cob.userservice.api.workflow.delete.service.ScheduledConsultantMaintenance.AcknowledgedConsultantDeletion;
import java.util.List;
import java.util.Objects;
import lombok.Data;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Data
public class ConsultantDeletionWorkflowDTO {

  private Consultant consultant;
  private List<DeletionWorkflowError> deletionWorkflowErrors;
  private final AcknowledgedConsultantDeletion acknowledgedDeletion;

  public ConsultantDeletionWorkflowDTO(Consultant consultant, List<DeletionWorkflowError> errors) {
    this.consultant = consultant;
    this.deletionWorkflowErrors = errors;
    this.acknowledgedDeletion = null;
  }

  public ConsultantDeletionWorkflowDTO(
      Consultant consultant,
      List<DeletionWorkflowError> errors,
      AcknowledgedConsultantDeletion acknowledged) {
    this.consultant = Objects.requireNonNull(consultant);
    this.deletionWorkflowErrors = Objects.requireNonNull(errors);
    this.acknowledgedDeletion = Objects.requireNonNull(acknowledged);
  }

  public boolean isAcknowledged() {
    return acknowledgedDeletion != null;
  }

  /** Data alone cannot authorize cleanup; compare all53 through the same actual private owner. */
  public void requireOwnedConsultant() {
    if (acknowledgedDeletion == null) throw new ScopedIdentityCommandRequiredException();
    acknowledgedDeletion.requireCurrentConsultant(consultant);
  }

  public void runOwnedLocal(Runnable action) {
    if (acknowledgedDeletion == null) throw new ScopedIdentityCommandRequiredException();
    acknowledgedDeletion.runOwnedLocal(
        () -> {
          requireOwnedConsultant();
          action.run();
          requireOwnedConsultant();
        });
  }

  public void runOwnedExternal(Runnable action) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new ScopedIdentityCommandRequiredException();
    requireOwnedConsultant();
    action.run();
    requireOwnedConsultant();
  }

  public boolean completeOwnedLocalDeletion(Runnable action) {
    if (acknowledgedDeletion == null) throw new ScopedIdentityCommandRequiredException();
    return acknowledgedDeletion.completeOwnedLocalDeletion(
        () -> {
          requireOwnedConsultant();
          action.run();
        });
  }
}
