package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.SessionTopicRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.session.DeletedAskerSessionService;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.service.TeamDiscussionPurgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Delete action for sessions of a {@link User}. */
@Slf4j
@Component
public class DeleteAskerRoomsAndSessionsAction extends DeleteRoomsAndSessionAction
    implements ActionCommand<AskerDeletionWorkflowDTO> {

  private final DeletedAskerSessionService deletedAskerSessionService;

  public DeleteAskerRoomsAndSessionsAction(
      SessionRepository sessionRepository,
      SessionDataRepository sessionDataRepository,
      CaseHandoverRequestRepository caseHandoverRequestRepository,
      SessionSupervisorRepository sessionSupervisorRepository,
      SessionTopicRepository sessionTopicRepository,
      TeamDiscussionRepository teamDiscussionRepository,
      TeamDiscussionPurgeService teamDiscussionPurgeService,
      DeletedAskerSessionService deletedAskerSessionService) {
    super(
        sessionRepository,
        sessionDataRepository,
        caseHandoverRequestRepository,
        sessionSupervisorRepository,
        sessionTopicRepository,
        teamDiscussionRepository,
        teamDiscussionPurgeService);
    this.deletedAskerSessionService = deletedAskerSessionService;
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
            session -> {
              // ORISO-Frontend#1159: leave the counsellor a placeholder before the case vanishes.
              recordPlaceholder(session);
              performSessionDeletion(session, actionTarget.getDeletionWorkflowErrors());
            });
  }

  private void recordPlaceholder(Session session) {
    try {
      deletedAskerSessionService.recordDeletion(session);
    } catch (RuntimeException ex) {
      log.warn("Could not record deleted-asker placeholder for session {}", session.getId(), ex);
    }
  }
}
