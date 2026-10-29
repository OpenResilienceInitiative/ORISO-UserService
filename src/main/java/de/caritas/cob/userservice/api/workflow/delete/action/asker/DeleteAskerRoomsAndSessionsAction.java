package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import de.caritas.cob.userservice.api.actions.ActionCommand;
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
              // ORISO-Frontend#1159: capture what the counsellor's placeholder needs while the
              // session still exists, and record it only if the session really was deleted.
              var placeholder = DeletedAskerSessionService.Placeholder.of(session);
              var errorsBefore = actionTarget.getDeletionWorkflowErrors().size();
              performSessionDeletion(session, actionTarget.getDeletionWorkflowErrors());
              if (actionTarget.getDeletionWorkflowErrors().size() == errorsBefore) {
                placeholder.ifPresent(this::recordPlaceholder);
              }
            });
  }

  private void recordPlaceholder(DeletedAskerSessionService.Placeholder placeholder) {
    try {
      deletedAskerSessionService.recordDeletionAfterCommit(placeholder);
    } catch (RuntimeException ex) {
      log.warn(
          "Could not record deleted-asker placeholder for session {}",
          placeholder.formerSessionId(),
          ex);
    }
  }
}
