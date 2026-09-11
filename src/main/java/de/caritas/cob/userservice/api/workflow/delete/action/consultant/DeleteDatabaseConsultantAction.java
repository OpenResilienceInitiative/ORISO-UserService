package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantMobileTokenRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.workflow.delete.action.UserContentCleanup;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Deletes a {@link Consultant} in database. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteDatabaseConsultantAction
    implements ActionCommand<ConsultantDeletionWorkflowDTO> {

  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull SessionSupervisorRepository sessionSupervisorRepository;
  private final @NonNull ConsultantMobileTokenRepository consultantMobileTokenRepository;
  private final @NonNull IdentityTombstoneService identityTombstoneService;

  /**
   * Deletes the given {@link Consultant} in database, together with the dependencies that hold a
   * restricting foreign key on it.
   *
   * <p>{@code session_supervisor} references the consultant through both {@code
   * supervisor_consultant_id} and {@code added_by_consultant_id}, and both columns are {@code NOT
   * NULL}. A supervision row therefore cannot outlive either consultant it points at, so the row is
   * deleted rather than detached. Where the deleted consultant was only the one who <em>added</em>
   * a supervision performed by somebody else, that supervision is lost as collateral — making
   * {@code added_by_consultant_id} nullable in a migration would allow it to survive.
   *
   * <p>The row is also kept when clearing the consultant's unencrypted content (drafts,
   * notification feed) failed earlier in the workflow. That content is keyed by the consultant, so
   * deleting the account row would orphan it beyond any retry — the row is what the next scheduler
   * run needs to try again (#983, KDG epic #1010).
   *
   * @param actionTarget the {@link ConsultantDeletionWorkflowDTO} with the {@link Consultant} to
   *     delete
   */
  @Override
  public void execute(ConsultantDeletionWorkflowDTO actionTarget) {
    if (UserContentCleanup.failed(actionTarget.getDeletionWorkflowErrors())) {
      log.warn("Keeping consultant account row: unencrypted user content could not be deleted");
      return;
    }

    try {
      this.sessionRepository
          .findByConsultantAndStatusIn(
              actionTarget.getConsultant(),
              Lists.newArrayList(SessionStatus.NEW, SessionStatus.INITIAL))
          .stream()
          .forEach(this::unassignConsultantFromSession);
    } catch (Exception e) {
      handleExceptionWithMessage(
          actionTarget,
          e,
          "Unable to unassign consultant from his sessions with state NEW or INITIAL");
      return;
    }

    try {
      this.sessionSupervisorRepository.deleteAllByConsultantId(
          actionTarget.getConsultant().getId());
    } catch (Exception e) {
      handleExceptionWithMessage(actionTarget, e, "Unable to delete consultant supervisions");
      return;
    }

    try {
      var mobileTokens =
          this.consultantMobileTokenRepository.findByConsultant(actionTarget.getConsultant());
      this.consultantMobileTokenRepository.deleteAll(mobileTokens);
    } catch (Exception e) {
      handleExceptionWithMessage(actionTarget, e, "Unable to delete consultant mobile tokens");
      return;
    }

    try {
      identityTombstoneService.recordDeletedConsultant(actionTarget.getConsultant());
      this.consultantRepository.delete(actionTarget.getConsultant());
    } catch (Exception e) {
      handleExceptionWithMessage(actionTarget, e, "Unable to delete consultant in database");
    }
  }

  private static void handleExceptionWithMessage(
      ConsultantDeletionWorkflowDTO actionTarget, Exception e, String message) {
    log.error("UserService delete workflow error: ", e);
    actionTarget
        .getDeletionWorkflowErrors()
        .add(
            DeletionWorkflowError.builder()
                .deletionSourceType(CONSULTANT)
                .deletionTargetType(DeletionTargetType.DATABASE)
                .identifier(actionTarget.getConsultant().getId())
                .reason(message)
                .timestamp(nowInUtc())
                .build());
  }

  private void unassignConsultantFromSession(Session session) {
    session.setConsultant(null);
    sessionRepository.save(session);
  }
}
