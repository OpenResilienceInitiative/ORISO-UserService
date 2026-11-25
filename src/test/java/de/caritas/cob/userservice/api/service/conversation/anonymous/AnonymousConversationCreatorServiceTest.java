package de.caritas.cob.userservice.api.service.conversation.anonymous;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_DTO_SUCHT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.conversation.service.AnonymousConversationCreatorService;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.List;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnonymousConversationCreatorServiceTest {

  @InjectMocks private AnonymousConversationCreatorService service;
  @Mock private SessionService sessionService;
  @Mock private AgencyService agencyService;
  @Mock private ConsultantAgencyService consultantAgencyService;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private TopicConsultantRoutingService topicConsultantRoutingService;

  private final EasyRandom easyRandom = new EasyRandom();

  @Test
  void createsWaitingLiveChatSessionWithoutTransportRoom() {
    var session = easyRandom.nextObject(Session.class);
    session.setMatrixRoomId(null);
    session.setMainTopicId(11L);
    when(sessionService.initializeSession(
            any(User.class),
            any(UserDTO.class),
            anyBoolean(),
            any(RegistrationType.class),
            any(SessionStatus.class)))
        .thenReturn(session);
    when(topicConsultantRoutingService.findEligibleConsultantIds(session.getMainTopicId()))
        .thenReturn(List.of("consultant-id"));
    when(consultantAgencyService.getConsultantAgenciesByConsultantIds(List.of("consultant-id")))
        .thenReturn(List.of());

    var created = service.createAnonymousConversation(USER_DTO_SUCHT, USER);

    assertThat(created).isSameAs(session);
    assertThat(created.getConversationType()).isEqualTo(ConversationType.LIVE_CHAT);
    assertThat(created.getMatrixRoomId()).isNull();
    verify(sessionService).saveSession(session);
    verify(eventNotificationService)
        .createWaitingRoomClientJoinedNotifications(session, List.of("consultant-id"));
  }

  @Test
  void keepsCreatedConversationWhenWaitingRoomNotificationPersistenceFails() {
    var session = easyRandom.nextObject(Session.class);
    session.setMatrixRoomId(null);
    session.setMainTopicId(11L);
    when(sessionService.initializeSession(
            any(User.class),
            any(UserDTO.class),
            anyBoolean(),
            any(RegistrationType.class),
            any(SessionStatus.class)))
        .thenReturn(session);
    when(topicConsultantRoutingService.findEligibleConsultantIds(session.getMainTopicId()))
        .thenReturn(List.of("consultant-id"));
    when(consultantAgencyService.getConsultantAgenciesByConsultantIds(List.of("consultant-id")))
        .thenReturn(List.of());
    doThrow(new IllegalStateException("notification database unavailable"))
        .when(eventNotificationService)
        .createWaitingRoomClientJoinedNotifications(session, List.of("consultant-id"));

    assertThatCode(() -> service.createAnonymousConversation(USER_DTO_SUCHT, USER))
        .doesNotThrowAnyException();

    verify(sessionService).saveSession(session);
    verify(eventNotificationService)
        .createWaitingRoomClientJoinedNotifications(session, List.of("consultant-id"));
  }

  @Test
  void reportsAFailedSessionAndLeavesTheUndoToTheRegistrationEntry() {
    when(sessionService.initializeSession(
            any(User.class),
            any(UserDTO.class),
            anyBoolean(),
            any(RegistrationType.class),
            any(SessionStatus.class)))
        .thenThrow(new IllegalArgumentException("database unavailable"));

    assertThatThrownBy(() -> service.createAnonymousConversation(USER_DTO_SUCHT, USER))
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("Could not create the live-chat session");

    verify(sessionService, never()).saveSession(any());
    verifyNoInteractions(eventNotificationService);
  }
}
