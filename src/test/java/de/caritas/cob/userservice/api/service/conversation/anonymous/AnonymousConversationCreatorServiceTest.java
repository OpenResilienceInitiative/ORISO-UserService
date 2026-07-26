package de.caritas.cob.userservice.api.service.conversation.anonymous;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_DTO_SUCHT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.conversation.model.AnonymousUserCredentials;
import de.caritas.cob.userservice.api.conversation.service.AnonymousConversationCreatorService;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService;
import de.caritas.cob.userservice.api.service.liveevents.LiveEventNotificationService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.util.List;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnonymousConversationCreatorServiceTest {

  @InjectMocks private AnonymousConversationCreatorService service;
  @Mock private UserService userService;
  @Mock private SessionService sessionService;
  @Mock private RollbackFacade rollbackFacade;
  @Mock private AgencyService agencyService;
  @Mock private ConsultantAgencyService consultantAgencyService;
  @Mock private LiveEventNotificationService liveEventNotificationService;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private TopicConsultantRoutingService topicConsultantRoutingService;

  private final EasyRandom easyRandom = new EasyRandom();

  @Test
  void createsWaitingLiveChatSessionWithoutTransportRoom() {
    var session = easyRandom.nextObject(Session.class);
    session.setGroupId(null);
    session.setMatrixRoomId(null);
    session.setMainTopicId(11L);
    var credentials = AnonymousUserCredentials.builder().userId(USER.getUserId()).build();
    when(userService.getUser(credentials.getUserId())).thenReturn(Optional.of(USER));
    when(sessionService.initializeSession(
            any(User.class),
            any(UserDTO.class),
            anyBoolean(),
            any(RegistrationType.class),
            any(SessionStatus.class)))
        .thenReturn(session);
    when(topicConsultantRoutingService.findEligibleConsultantIds(
            session.getMainTopicId(), session.getConsultingTypeId()))
        .thenReturn(List.of("consultant-id"));
    when(consultantAgencyService.getConsultantAgenciesByConsultantIds(List.of("consultant-id")))
        .thenReturn(List.of());

    var created = service.createAnonymousConversation(USER_DTO_SUCHT, credentials);

    assertThat(created).isSameAs(session);
    assertThat(created.getConversationType()).isEqualTo(ConversationType.LIVE_CHAT);
    assertThat(created.getGroupId()).isNull();
    assertThat(created.getMatrixRoomId()).isNull();
    verify(sessionService).saveSession(session);
    verify(liveEventNotificationService)
        .sendLiveNewAnonymousEnquiryEventToUsers(List.of("consultant-id"), session.getId());
    verify(eventNotificationService)
        .createWaitingRoomClientJoinedNotifications(session, List.of("consultant-id"));
  }

  @Test
  void rejectsMissingAnonymousUserBeforeCreatingSession() {
    when(userService.getUser(anyString())).thenReturn(Optional.empty());
    var credentials = easyRandom.nextObject(AnonymousUserCredentials.class);

    assertThatThrownBy(() -> service.createAnonymousConversation(USER_DTO_SUCHT, credentials))
        .isInstanceOf(InternalServerErrorException.class);

    verifyNoInteractions(sessionService, rollbackFacade);
  }

  @Test
  void rollsBackUserWhenSessionInitializationFails() {
    when(userService.getUser(anyString())).thenReturn(Optional.of(USER));
    when(sessionService.initializeSession(
            any(User.class),
            any(UserDTO.class),
            anyBoolean(),
            any(RegistrationType.class),
            any(SessionStatus.class)))
        .thenThrow(new IllegalArgumentException("database unavailable"));
    var credentials = easyRandom.nextObject(AnonymousUserCredentials.class);

    assertThatThrownBy(() -> service.createAnonymousConversation(USER_DTO_SUCHT, credentials))
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("Could not create session");

    verify(rollbackFacade).rollBackUserAccount(any());
    verify(sessionService, never()).saveSession(any());
    verifyNoInteractions(liveEventNotificationService, eventNotificationService);
  }
}
