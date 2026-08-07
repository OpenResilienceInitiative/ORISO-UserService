package de.caritas.cob.userservice.api.actions.session;

import static de.caritas.cob.userservice.api.service.notification.EventNotificationService.CATEGORY_SYSTEM;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Action to persist a finished anonymous conversation event for the other participants. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SendFinishedAnonymousConversationEventActionCommand implements ActionCommand<Session> {

  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  /** Persists a finished anonymous conversation event. */
  @Override
  public void execute(Session session) {
    collectNotInitiatingUser(session)
        .forEach(recipientId -> persistFinishedEvent(recipientId, session));
  }

  private void persistFinishedEvent(String recipientId, Session session) {
    try {
      eventNotificationService.createEvent(
          recipientId,
          "conversation.finished",
          CATEGORY_SYSTEM,
          "Conversation finished",
          "The anonymous conversation has ended.",
          null,
          session.getId(),
          session.getTenantId());
    } catch (RuntimeException exception) {
      log.error(
          "Unable to persist anonymous conversation finished event for session {}",
          session.getId(),
          exception);
    }
  }

  private List<String> collectNotInitiatingUser(Session session) {
    if (hasSessionOnlyUser(session)) {
      return singletonList(session.getUser().getUserId());
    }
    if (doesSessionHaveConsultantAndUser(session)) {
      return obtainNotInitiatingUsers(session);
    }
    return emptyList();
  }

  private boolean hasSessionOnlyUser(Session session) {
    return isNull(session.getConsultant()) && nonNull(session.getUser());
  }

  private boolean doesSessionHaveConsultantAndUser(Session session) {
    return nonNull(session.getConsultant()) && nonNull(session.getUser());
  }

  private List<String> obtainNotInitiatingUsers(Session session) {
    return Stream.of(session.getConsultant().getId(), session.getUser().getUserId())
        .filter(this::notInitiatingUser)
        .collect(Collectors.toList());
  }

  private boolean notInitiatingUser(String userId) {
    try {
      return !userId.equals(this.authenticatedUser.getUserId());
    } catch (Exception e) {
      return true;
    }
  }
}
