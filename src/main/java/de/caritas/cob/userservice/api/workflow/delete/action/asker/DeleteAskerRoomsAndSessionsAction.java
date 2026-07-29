package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.SessionTopicRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import org.springframework.stereotype.Component;

/** Delete action for sessions of a {@link User}. */
@Component
public class DeleteAskerRoomsAndSessionsAction extends DeleteRoomsAndSessionAction
    implements ActionCommand<AskerDeletionWorkflowDTO> {

  public DeleteAskerRoomsAndSessionsAction(
      SessionRepository sessionRepository,
      SessionDataRepository sessionDataRepository,
      CaseHandoverRequestRepository caseHandoverRequestRepository,
      SessionSupervisorRepository sessionSupervisorRepository,
      SessionTopicRepository sessionTopicRepository) {
    super(
        sessionRepository,
        sessionDataRepository,
        caseHandoverRequestRepository,
        sessionSupervisorRepository,
        sessionTopicRepository);
  }

  /**
   * Deletes all sessions in the database of a given {@link User}.
   *
   * @param actionTarget the {@link AskerDeletionWorkflowDTO} with the user for session and room
   *     deletion
   */
  @Override
  public void execute(AskerDeletionWorkflowDTO actionTarget) {
    this.sessionRepository
        .findByUser(actionTarget.getUser())
        .forEach(
            session -> performSessionDeletion(session, actionTarget.getDeletionWorkflowErrors()));
  }
}
