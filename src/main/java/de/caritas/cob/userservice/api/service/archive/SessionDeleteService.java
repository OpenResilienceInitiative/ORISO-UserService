package de.caritas.cob.userservice.api.service.archive;

import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.actions.user.DeactivateKeycloakUserActionCommand;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.ArchiveOrDeleteSessionStatisticsEvent;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteSingleRoomAndSessionAction;
import de.caritas.cob.userservice.api.workflow.delete.model.SessionDeletionWorkflowDTO;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionDeleteService {

  private final @NotNull ActionsRegistry actionsRegistry;

  private final @NotNull SessionService sessionService;

  private final @NonNull StatisticsService statisticsService;

  private final @NonNull MatrixSessionSystemMessageService matrixSessionSystemMessageService;

  private final @NonNull MembersOnlyChatAccess membersOnlyChatAccess;

  private final @NonNull AuthenticatedUser authenticatedUser;

  @Transactional
  public void deleteSession(Long sessionId) {
    log.info("Deleting session with id {}", sessionId);

    var session =
        sessionService
            .getSession(sessionId)
            .orElseThrow(
                () -> new NotFoundException("A session with an id %s does not exist.", sessionId));

    // Whoever is no participant of a members-only chat gets nothing; a participant ends the case
    // by stopping the chat, which retires the session with it, never by deleting the session
    // under a chat that stays open.
    membersOnlyChatAccess.requireAccess(session, authenticatedUser.getUserId());
    if (membersOnlyChatAccess.concerns(session)) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.MEMBERS_ONLY_CHAT_STOP_REQUIRED, HttpStatus.CONFLICT);
    }

    try {
      var endedBy =
          authenticatedUser.isConsultant()
              ? MatrixSessionSystemMessageService.EndedBy.CONSULTANT
              : MatrixSessionSystemMessageService.EndedBy.ADVICE_SEEKER;
      matrixSessionSystemMessageService.postUserLeftChatMessage(session, endedBy);
    } catch (Exception e) {
      log.warn("Could not post Matrix user-left message before deleting session {}", sessionId, e);
    }

    var user = session.getUser();
    if (user.getSessions().size() == 1) {
      actionsRegistry
          .buildContainerForType(User.class)
          .addActionToExecute(DeactivateKeycloakUserActionCommand.class)
          .executeActions(user);
    }

    var deleteSession = new SessionDeletionWorkflowDTO(session, null);
    actionsRegistry
        .buildContainerForType(SessionDeletionWorkflowDTO.class)
        .addActionToExecute(DeleteSingleRoomAndSessionAction.class)
        .executeActions(deleteSession);

    fireArchiveOrDeleteSessionEvent(session);
    log.info("Session with id {} deleted", sessionId);
  }

  private void fireArchiveOrDeleteSessionEvent(Session session) {
    try {
      ArchiveOrDeleteSessionStatisticsEvent archiveOrDeleteSessionStatisticsEvent =
          new ArchiveOrDeleteSessionStatisticsEvent(
              session.getUser(), session.getId(), LocalDateTime.now());
      statisticsService.fireEvent(archiveOrDeleteSessionStatisticsEvent);
    } catch (Exception e) {
      log.error("Could not create session archive statistics event", e);
    }
  }
}
