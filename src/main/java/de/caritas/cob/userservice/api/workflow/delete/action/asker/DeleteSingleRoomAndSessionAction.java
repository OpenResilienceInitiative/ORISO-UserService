package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.SessionTopicRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.SessionDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.service.TeamDiscussionPurgeService;
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
      SessionTopicRepository sessionTopicRepository,
      TeamDiscussionRepository teamDiscussionRepository,
      TeamDiscussionPurgeService teamDiscussionPurgeService) {
    super(
        sessionRepository,
        sessionDataRepository,
        caseHandoverRequestRepository,
        sessionSupervisorRepository,
        sessionTopicRepository,
        teamDiscussionRepository,
        teamDiscussionPurgeService);
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
