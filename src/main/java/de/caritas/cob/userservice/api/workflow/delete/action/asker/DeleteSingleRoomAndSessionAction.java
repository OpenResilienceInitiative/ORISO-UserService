package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.SessionTopicRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.SessionDeletionWorkflowDTO;
import org.springframework.stereotype.Component;

@Component
public class DeleteSingleRoomAndSessionAction extends DeleteRoomsAndSessionAction
    implements ActionCommand<SessionDeletionWorkflowDTO> {

  /**
   * Constructor.
   *
   * @param sessionRepository a {@link SessionRepository} instance
   * @param sessionDataRepository a {@link SessionDataRepository} instance
   * @param caseHandoverRequestRepository a {@link CaseHandoverRequestRepository} instance
   */
  public DeleteSingleRoomAndSessionAction(
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
   * Deletes the given {@link Session} and its related database records.
   *
   * @param actionTarget the {@link SessionDeletionWorkflowDTO} with the session to delete
   */
  @Override
  public void execute(SessionDeletionWorkflowDTO actionTarget) {
    performSessionDeletion(actionTarget.getSession(), actionTarget.getDeletionWorkflowErrors());
  }
}
