package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantMobileTokenRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService;
import de.caritas.cob.userservice.api.workflow.delete.action.UserContentCleanup;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionAuditService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.util.List;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Deletes a {@link Consultant} in database. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteDatabaseConsultantAction
    implements ActionCommand<ConsultantDeletionWorkflowDTO> {

  private static final String RELEASED_USERNAME_PREFIX = "deleted:";

  private static final List<SessionStatus> ALL_SESSION_STATUSES = List.of(SessionStatus.values());

  /** MariaDB: {@code CONSTRAINT `session_ibfk_2`}; H2: {@code violation: "SESSION_IBFK_2: ...}. */
  private static final Pattern CONSTRAINT_NAME =
      Pattern.compile("CONSTRAINT `([A-Za-z0-9_]+)`|constraint violation: \"([A-Za-z0-9_]+):");

  private final @NonNull PlatformTransactionManager transactionManager;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull SessionOwnershipService sessionOwnershipService;
  private final @NonNull SessionSupervisorRepository sessionSupervisorRepository;
  private final @NonNull ConsultantMobileTokenRepository consultantMobileTokenRepository;
  private final @NonNull IdentityTombstoneService identityTombstoneService;
  private final @NonNull DeletionAuditService deletionAuditService;

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
    releaseUsername(actionTarget);

    if (UserContentCleanup.failed(actionTarget.getDeletionWorkflowErrors())) {
      log.warn("Keeping consultant account row: unencrypted user content could not be deleted");
      return;
    }

    // One transaction for every database step (ORISO-Admin#1523). The job calls this outside a
    // transaction; the supervision bulk delete then threw on every run, so not one counsellor row
    // was ever deleted. Together they also commit or roll back as one: no tombstone for a kept row.
    var consultant = actionTarget.getConsultant();
    var step = new String[] {"unassign the counsellor from their sessions"};
    try {
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(
              status -> {
                // Marking already refused running or archived sessions unless the admin chose to
                // delete them anyway, so every session left keeps its history without an owner.
                sessionOwnershipService.clearOwnerFromSessions(consultant, ALL_SESSION_STATUSES);
                step[0] = "delete the counsellor's supervisions";
                sessionSupervisorRepository.deleteAllByConsultantId(consultant.getId());
                step[0] = "delete the counsellor's mobile tokens";
                consultantMobileTokenRepository.deleteAll(
                    consultantMobileTokenRepository.findByConsultant(consultant));
                step[0] = "delete the counsellor in the database";
                identityTombstoneService.recordDeletedConsultant(consultant);
                // The managed row, not the claim's detached copy: removing it cascades to its
                // topics and languages, which a merge of the stale copy cannot do.
                consultantRepository
                    .findById(consultant.getId())
                    .ifPresent(consultantRepository::delete);
                consultantRepository.flush();
              });
    } catch (Exception e) {
      handleExceptionWithMessage(actionTarget, e, "Unable to " + step[0] + blockingReference(e));
      return;
    }

    // A rollback of a half-created account (RollbackFacade) runs this action too, but nobody
    // deleted that account: only a deletion that went through the lifecycle is logged.
    if (!wentThroughDeletionLifecycle(actionTarget.getConsultant())) {
      return;
    }
    // The scheduler is the actor of the hard delete; the scheduling admin is on the SCHEDULED row.
    deletionAuditService.recordAsSystem(
        DeletionAuditTargetType.CONSULTANT,
        actionTarget.getConsultant().getId(),
        actionTarget.getConsultant().getTenantId(),
        outcomeOf(actionTarget));
  }

  /**
   * Frees the username before anything else, in its own transaction. {@code consultant.username} is
   * unique: when the row delete below fails (a reference that blocks it, kept user content), the
   * row used to keep the name, and nobody could create an account with it again (ORISO-Admin#1489).
   * The hard deletion only runs after the read-only safeguard window, so the deletion can no longer
   * be cancelled and the name is not needed any more. A failure here is recorded and the deletion
   * goes on: the row may still be deleted, which frees the name as well.
   */
  private void releaseUsername(ConsultantDeletionWorkflowDTO actionTarget) {
    var consultant = actionTarget.getConsultant();
    if (!wentThroughDeletionLifecycle(consultant)) {
      return;
    }
    if (externalAccountsStillHeld(actionTarget)) {
      // Keycloak or the Matrix account could not be removed in this run. A new counsellor with the
      // name would then get no chat identity (the still active Matrix account cannot be
      // reactivated for them, and the retained row blocks the repair). The next run retries both
      // and releases the name once they are gone.
      return;
    }
    try {
      consultantRepository.releaseUsernameOfDeletedConsultant(
          consultant.getId(), releasedUsernameOf(consultant.getId()));
    } catch (Exception e) {
      handleExceptionWithMessage(actionTarget, e, "Unable to release the consultant username");
    }
  }

  private static boolean externalAccountsStillHeld(ConsultantDeletionWorkflowDTO actionTarget) {
    var matrixUserId = actionTarget.getConsultant().getMatrixUserId();
    return actionTarget.getDeletionWorkflowErrors().stream()
        .anyMatch(
            error ->
                error.getDeletionTargetType() == DeletionTargetType.KEYCLOAK
                    || (error.getDeletionTargetType() == DeletionTargetType.MATRIX
                        && matrixUserId != null
                        && matrixUserId.equals(error.getIdentifier())));
  }

  /** Unique because the consultant id is; cannot be an encoded ("enc.") or valid plain username. */
  static String releasedUsernameOf(String consultantId) {
    return RELEASED_USERNAME_PREFIX + consultantId;
  }

  /**
   * Whether this row's username was already released. That only happens in a run that deactivated
   * the counsellor's Matrix account, so the account may since belong to someone else.
   */
  public static boolean hasReleasedUsername(Consultant consultant) {
    var username = consultant.getUsername();
    return username != null && username.startsWith(RELEASED_USERNAME_PREFIX);
  }

  /**
   * EXECUTED promises that the account is gone. When an earlier step (Keycloak, Matrix, ...)
   * failed, the row is deleted all the same and cannot be purged again, so the log keeps the entry
   * but says PARTIAL_FAILURE: the login or chat account may still exist (#1525).
   */
  private static DeletionAuditOutcome outcomeOf(ConsultantDeletionWorkflowDTO actionTarget) {
    return actionTarget.getDeletionWorkflowErrors().isEmpty()
        ? DeletionAuditOutcome.EXECUTED
        : DeletionAuditOutcome.PARTIAL_FAILURE;
  }

  private static boolean wentThroughDeletionLifecycle(Consultant consultant) {
    var state = consultant.getDeletionLifecycleState();
    return state != null && state != DeletionLifecycleState.ACTIVE;
  }

  /**
   * Names the foreign key that blocked the delete, e.g. {@code ": blocked by session_ibfk_2"}, so a
   * kept counsellor shows which reference to clear (epic ORISO-Admin#1520, rule 6). The constraint
   * name is schema text, never personal data; anything else reads as an empty suffix.
   */
  static String blockingReference(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      var message = cause.getMessage();
      if (message == null) {
        continue;
      }
      var matcher = CONSTRAINT_NAME.matcher(message);
      if (matcher.find()) {
        return ": blocked by "
            + (matcher.group(1) != null
                ? matcher.group(1)
                : matcher.group(2).toLowerCase(java.util.Locale.ROOT));
      }
    }
    return "";
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
}
