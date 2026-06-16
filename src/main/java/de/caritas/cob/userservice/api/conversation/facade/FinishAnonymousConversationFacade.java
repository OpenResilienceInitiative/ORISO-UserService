package de.caritas.cob.userservice.api.conversation.facade;

import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.actions.session.DeactivateSessionActionCommand;
import de.caritas.cob.userservice.api.actions.session.PostConversationFinishedAliasMessageActionCommand;
import de.caritas.cob.userservice.api.actions.session.SendFinishedAnonymousConversationEventActionCommand;
import de.caritas.cob.userservice.api.actions.session.SetRocketChatRoomReadOnlyActionCommand;
import de.caritas.cob.userservice.api.actions.user.DeactivateKeycloakUserActionCommand;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.session.SessionService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Facade to encapsulate logic to finish an anonymous conversation. */
@Service
@RequiredArgsConstructor
public class FinishAnonymousConversationFacade {

  private final @NonNull SessionService sessionService;
  private final @NonNull ActionsRegistry actionsRegistry;
  private final @NonNull AuthenticatedUser authenticatedUser;

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
    this.actionsRegistry
        .buildContainerForType(Session.class)
        .addActionToExecute(PostConversationFinishedAliasMessageActionCommand.class)
        .executeActions(session);

    this.actionsRegistry
        .buildContainerForType(User.class)
        .addActionToExecute(DeactivateKeycloakUserActionCommand.class)
        .executeActions(session.getUser());

    this.actionsRegistry
        .buildContainerForType(Session.class)
        .addActionToExecute(DeactivateSessionActionCommand.class)
        .addActionToExecute(SetRocketChatRoomReadOnlyActionCommand.class)
        .addActionToExecute(SendFinishedAnonymousConversationEventActionCommand.class)
        .executeActions(session);
  }

  private void verifyPermissionToFinish(Session session) {
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
