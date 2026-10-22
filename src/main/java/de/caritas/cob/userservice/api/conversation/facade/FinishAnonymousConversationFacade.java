package de.caritas.cob.userservice.api.conversation.facade;

import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.actions.session.DeactivateSessionActionCommand;
import de.caritas.cob.userservice.api.actions.session.SendFinishedAnonymousConversationEventActionCommand;
import de.caritas.cob.userservice.api.actions.user.DeactivateKeycloakUserActionCommand;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService.EndedBy;
import de.caritas.cob.userservice.api.service.session.SessionService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Facade to encapsulate logic to finish an anonymous conversation. */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinishAnonymousConversationFacade {

  private final @NonNull SessionService sessionService;
  private final @NonNull ActionsRegistry actionsRegistry;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull MembersOnlyChatAccess membersOnlyChatAccess;
  private final @NonNull MatrixSessionSystemMessageService matrixSessionSystemMessageService;

  /**
   * Finishes the anonymous session with given id.
   *
   * @param sessionId the session id
   */
  public void finishConversation(Long sessionId) {
    var session =
        this.sessionService
            .getSession(sessionId)
            .orElseThrow(
                () -> new NotFoundException("Session with id %s does not exist", sessionId));

    verifyPermissionToFinish(session);

    // Notify the room first while user/consultant Matrix credentials are still valid.
    postUserLeftChatMessage(session);

    this.actionsRegistry
        .buildContainerForType(User.class)
        .addActionToExecute(DeactivateKeycloakUserActionCommand.class)
        .executeActions(session.getUser());

    this.actionsRegistry
        .buildContainerForType(Session.class)
        .addActionToExecute(DeactivateSessionActionCommand.class)
        .addActionToExecute(SendFinishedAnonymousConversationEventActionCommand.class)
        .executeActions(session);
  }

  private void postUserLeftChatMessage(Session session) {
    var endedBy = authenticatedUser.isConsultant() ? EndedBy.CONSULTANT : EndedBy.ADVICE_SEEKER;
    try {
      matrixSessionSystemMessageService.postUserLeftChatMessage(session, endedBy);
    } catch (Exception e) {
      // A Matrix failure must never block finishing the conversation.
      log.error("Unable to post Matrix user-left message for session {}", session.getId(), e);
    }
  }

  private void verifyPermissionToFinish(Session session) {
    // The technical session of a members-only chat looks like an anonymous one; finishing it would
    // deactivate the shared system account of the whole tenant.
    membersOnlyChatAccess.requireOrdinary(
        session, "The session of a members-only chat is not an anonymous conversation.");
    if (session.getRegistrationType() != ANONYMOUS
        && !sessionService.isAnonymousStyleRegistration(session)) {
      throw new ForbiddenException(
          "Session with id %s is not an anonymous conversation.", session.getId());
    }

    var userId = this.authenticatedUser.getUserId();

    if (this.authenticatedUser.isConsultant()) {
      if (nonNull(session.getConsultant()) && session.getConsultant().getId().equals(userId)) {
        return;
      }
      throw new ForbiddenException("Consultant is not assigned to session (%s).", session.getId());
    }

    if (nonNull(session.getUser()) && session.getUser().getUserId().equals(userId)) {
      return;
    }

    throw new ForbiddenException(
        "Access to session (%s) is limited to its advice seeker or assigned consultant.",
        session.getId());
  }
}
