package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.SessionTopicRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.service.TeamDiscussionPurgeService;
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
   * Deletes all sessions in the database of a given {@link User}.
   *
   * @param actionTarget the {@link AskerDeletionWorkflowDTO} with the user for session and room
   *     deletion
   */
  @Override
  public void execute(AskerDeletionWorkflowDTO actionTarget) {
    if (actionTarget.isAcknowledged()) {
      var sessions =
          new java.util.concurrent.atomic.AtomicReference<
              java.util.List<de.caritas.cob.userservice.api.model.Session>>();
      actionTarget.runOwnedLocal(
          () -> sessions.set(sessionRepository.findByUser(actionTarget.getUser())));
      for (var session : sessions.get()) {
        var errors = actionTarget.getDeletionWorkflowErrors();
        actionTarget.runOwnedLocal(() -> deleteSessionData(session, errors));
        actionTarget.runOwnedLocal(() -> deleteSessionSupervisors(session, errors));
        actionTarget.runOwnedLocal(() -> deleteSessionTopics(session, errors));
        actionTarget.runOwnedLocal(() -> deleteCaseHandoverRequests(session, errors));
        deleteTeamDiscussion(session, errors, actionTarget);
        actionTarget.runOwnedLocal(() -> deleteSession(session, errors));
      }
      return;
    }
    this.sessionRepository
        .findByUser(actionTarget.getUser())
        .forEach(
            session -> performSessionDeletion(session, actionTarget.getDeletionWorkflowErrors()));
  }
}
