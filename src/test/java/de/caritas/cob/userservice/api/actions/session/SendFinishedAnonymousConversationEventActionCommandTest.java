package de.caritas.cob.userservice.api.actions.session;

import static de.caritas.cob.userservice.api.service.notification.EventNotificationService.CATEGORY_SYSTEM;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.List;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SendFinishedAnonymousConversationEventActionCommandTest {

  @InjectMocks private SendFinishedAnonymousConversationEventActionCommand actionCommand;

  @Mock private AuthenticatedUser authenticatedUser;

  @Mock private EventNotificationService eventNotificationService;

  @ParameterizedTest
  @MethodSource("sessionsWithOnlyConsultantAndWithoutAnyUser")
  void execute_Should_useNoOtherServices_When_sessionHasNoUserOrOnlyConsultant(Session session) {
    this.actionCommand.execute(session);

    verifyNoMoreInteractions(this.authenticatedUser, this.eventNotificationService);
  }

  private static List<Session> sessionsWithOnlyConsultantAndWithoutAnyUser() {
    Session emptySession = new Session();
    Session onlyConsultantSession = new Session();
    onlyConsultantSession.setConsultant(new Consultant());

    return asList(emptySession, onlyConsultantSession);
  }

  @Test
  void execute_Should_persistFinishedEventForUser_When_consultantWasInitiator() {
    Session session = new EasyRandom().nextObject(Session.class);
    when(this.authenticatedUser.getUserId()).thenReturn(session.getConsultant().getId());

    this.actionCommand.execute(session);

    verifyFinishedEvent(session.getUser().getUserId(), session);
  }

  @Test
  void execute_Should_persistFinishedEventForConsultant_When_userWasInitiator() {
    Session session = new EasyRandom().nextObject(Session.class);
    when(this.authenticatedUser.getUserId()).thenReturn(session.getUser().getUserId());

    this.actionCommand.execute(session);

    verifyFinishedEvent(session.getConsultant().getId(), session);
  }

  @Test
  void execute_Should_persistFinishedEventForUser_When_sessionHasOnlyUser() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setConsultant(null);

    this.actionCommand.execute(session);

    verifyFinishedEvent(session.getUser().getUserId(), session);
  }

  @Test
  void execute_Should_persistFinishedEventForBothParticipants_When_systemWasInitiator() {
    Session session = new EasyRandom().nextObject(Session.class);
    when(this.authenticatedUser.getUserId()).thenThrow(new RuntimeException(""));

    this.actionCommand.execute(session);

    verifyFinishedEvent(session.getConsultant().getId(), session);
    verifyFinishedEvent(session.getUser().getUserId(), session);
  }

  @Test
  void execute_Should_continueForOtherParticipants_When_onePersistenceAttemptFails() {
    Session session = new EasyRandom().nextObject(Session.class);
    when(this.authenticatedUser.getUserId()).thenThrow(new RuntimeException("system action"));
    doThrow(new IllegalStateException("database unavailable"))
        .when(eventNotificationService)
        .createEvent(
            session.getConsultant().getId(),
            "conversation.finished",
            CATEGORY_SYSTEM,
            "Conversation finished",
            "The anonymous conversation has ended.",
            null,
            session.getId(),
            session.getTenantId());

    assertThatCode(() -> actionCommand.execute(session)).doesNotThrowAnyException();

    verifyFinishedEvent(session.getUser().getUserId(), session);
  }

  private void verifyFinishedEvent(String recipientId, Session session) {
    verify(eventNotificationService)
        .createEvent(
            recipientId,
            "conversation.finished",
            CATEGORY_SYSTEM,
            "Conversation finished",
            "The anonymous conversation has ended.",
            null,
            session.getId(),
            session.getTenantId());
  }
}
