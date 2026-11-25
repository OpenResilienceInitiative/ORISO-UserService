package de.caritas.cob.userservice.api.workflow.delete.model;

import de.caritas.cob.userservice.api.model.Session;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SessionDeletionWorkflowDTO {

  private Session session;
  private List<DeletionWorkflowError> deletionWorkflowErrors;

  /**
   * Keep the session row when one of its child cleanup steps failed, so a run that selects the
   * session again can retry (marked sessions of a deleted Beratungsstelle, ORISO-Admin#1524).
   */
  private boolean keepSessionOnChildFailure;

  public SessionDeletionWorkflowDTO(
      Session session, List<DeletionWorkflowError> deletionWorkflowErrors) {
    this(session, deletionWorkflowErrors, false);
  }
}
