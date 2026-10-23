package de.caritas.cob.userservice.api.workflow.delete.model;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
import de.caritas.cob.userservice.api.workflow.delete.service.ScheduledAskerMaintenance.AcknowledgedDeletion;
import java.util.List;
import java.util.Objects;
import lombok.Data;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Data
public class AskerDeletionWorkflowDTO {

  private User user;
  private List<DeletionWorkflowError> deletionWorkflowErrors;
  private final AcknowledgedDeletion acknowledgedDeletion;

  public AskerDeletionWorkflowDTO(User user, List<DeletionWorkflowError> errors) {
    this.user = user;
    this.deletionWorkflowErrors = errors;
    this.acknowledgedDeletion = null;
  }

  public AskerDeletionWorkflowDTO(
      User user, List<DeletionWorkflowError> errors, AcknowledgedDeletion acknowledged) {
    this.user = Objects.requireNonNull(user);
    this.deletionWorkflowErrors = Objects.requireNonNull(errors);
    this.acknowledgedDeletion = Objects.requireNonNull(acknowledged);
  }

  public boolean isAcknowledged() {
    return acknowledgedDeletion != null;
  }

  /** The DTO is data; only the same private frame can validate its full original User. */
  public void requireOwnedUser() {
    if (acknowledgedDeletion == null) throw new ScopedIdentityCommandRequiredException();
    acknowledgedDeletion.requireCurrentUser(user);
  }

  public void runOwnedLocal(Runnable action) {
    if (acknowledgedDeletion == null) throw new ScopedIdentityCommandRequiredException();
    acknowledgedDeletion.runOwnedLocal(
        () -> {
          requireOwnedUser();
          action.run();
          requireOwnedUser();
        });
  }

  public void runOwnedExternal(Runnable action) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new ScopedIdentityCommandRequiredException();
    requireOwnedUser();
    action.run();
    requireOwnedUser();
  }

  public boolean completeOwnedLocalDeletion(Runnable action) {
    if (acknowledgedDeletion == null) throw new ScopedIdentityCommandRequiredException();
    return acknowledgedDeletion.completeOwnedLocalDeletion(
        () -> {
          requireOwnedUser();
          action.run();
        });
  }
}
