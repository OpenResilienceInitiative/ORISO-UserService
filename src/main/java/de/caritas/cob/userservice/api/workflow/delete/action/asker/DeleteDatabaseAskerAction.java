package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserMobileTokenRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Action to delete a {@link User} in database. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteDatabaseAskerAction implements ActionCommand<AskerDeletionWorkflowDTO> {

  private final @NonNull UserRepository userRepository;
  private final @NonNull UserChatRepository userChatRepository;
  private final @NonNull UserMobileTokenRepository userMobileTokenRepository;
  private final @NonNull IdentityTombstoneService identityTombstoneService;

  /**
   * Deletes the given {@link User} in database, together with the dependencies that hold a
   * restricting foreign key on it.
   *
   * <p>{@code user_chat} and {@code user_mobile_token} both reference {@code user} with {@code ON
   * DELETE RESTRICT} and are not cascaded by JPA, so they have to go first or the user delete
   * fails. The chat itself is deliberately left alone — a group chat outlives any single
   * participant.
   *
   * @param actionTarget the {@link AskerDeletionWorkflowDTO} with the {@link User} to delete
   */
  @Override
  public void execute(AskerDeletionWorkflowDTO actionTarget) {
    if (!deleteChatMemberships(actionTarget) || !deleteMobileTokens(actionTarget)) {
      return;
    }

    try {
      identityTombstoneService.recordDeletedUser(actionTarget.getUser());
      this.userRepository.delete(actionTarget.getUser());
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      actionTarget
          .getDeletionWorkflowErrors()
          .add(
              DeletionWorkflowError.builder()
                  .deletionSourceType(ASKER)
                  .deletionTargetType(DeletionTargetType.DATABASE)
                  .identifier(actionTarget.getUser().getUserId())
                  .reason("Unable to delete user")
                  .timestamp(nowInUtc())
                  .build());
    }
  }

  private boolean deleteChatMemberships(AskerDeletionWorkflowDTO actionTarget) {
    try {
      var userChats = this.userChatRepository.findByUser(actionTarget.getUser());
      this.userChatRepository.deleteAll(userChats);
      return true;
    } catch (Exception e) {
      appendError(actionTarget, e, "Could not delete user chat memberships");
      return false;
    }
  }

  private boolean deleteMobileTokens(AskerDeletionWorkflowDTO actionTarget) {
    try {
      var mobileTokens = this.userMobileTokenRepository.findByUser(actionTarget.getUser());
      this.userMobileTokenRepository.deleteAll(mobileTokens);
      return true;
    } catch (Exception e) {
      appendError(actionTarget, e, "Could not delete user mobile tokens");
      return false;
    }
  }

  private void appendError(AskerDeletionWorkflowDTO actionTarget, Exception e, String reason) {
    log.error("UserService delete workflow error: ", e);
    actionTarget
        .getDeletionWorkflowErrors()
        .add(
            DeletionWorkflowError.builder()
                .deletionSourceType(ASKER)
                .deletionTargetType(DeletionTargetType.DATABASE)
                .identifier(actionTarget.getUser().getUserId())
                .reason(reason)
                .timestamp(nowInUtc())
                .build());
  }
}
