package de.caritas.cob.userservice.api.service.liveevents;

import static de.caritas.cob.userservice.liveservice.generated.web.model.EventType.ANONYMOUS_CONVERSATION_FINISHED;
import static de.caritas.cob.userservice.liveservice.generated.web.model.EventType.ANONYMOUS_ENQUIRY_ACCEPTED;
import static de.caritas.cob.userservice.liveservice.generated.web.model.EventType.DIRECT_MESSAGE;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.config.apiclient.LiveServiceApiControllerFactory;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.mobilepushmessage.MobilePushNotificationService;
import de.caritas.cob.userservice.liveservice.generated.ApiException;
import de.caritas.cob.userservice.liveservice.generated.web.LiveControllerApi;
import de.caritas.cob.userservice.liveservice.generated.web.model.EventType;
import de.caritas.cob.userservice.liveservice.generated.web.model.LiveEventMessage;
import de.caritas.cob.userservice.liveservice.generated.web.model.StatusSource;
import de.caritas.cob.userservice.liveservice.generated.web.model.StatusSource.FinishConversationPhaseEnum;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class LiveEventNotificationServiceTest {

  private static final LiveEventMessage MESSAGE = new LiveEventMessage().eventType(DIRECT_MESSAGE);

  @InjectMocks private LiveEventNotificationService liveEventNotificationService;

  @Mock private LiveControllerApi liveControllerApi;

  @Mock private UserIdsProviderFactory userIdsProviderFactory;

  @Mock private RelevantUserAccountIdsBySessionProvider bySessionProvider;

  @Mock private RelevantUserAccountIdsByChatProvider byChatProvider;

  @Mock private AuthenticatedUser authenticatedUser;

  @Mock private MobilePushNotificationService mobilePushNotificationService;

  @Mock private LiveServiceApiControllerFactory liveServiceApiControllerFactory;

  @BeforeEach
  public void setup() {
    when(liveServiceApiControllerFactory.createControllerApi()).thenReturn(liveControllerApi);
  }

  @Test
  public void sendLiveDirectMessageEventToUsers_Should_callFactoryAndLiveApi_When_rcGroupIdIsValid()
      throws ApiException {
    when(this.bySessionProvider.collectUserIds(any())).thenReturn(asList("1", "2"));
    when(this.userIdsProviderFactory.byRocketChatGroup(any())).thenReturn(bySessionProvider);

    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers("valid");

    verify(userIdsProviderFactory, times(1)).byRocketChatGroup("valid");
    verify(liveControllerApi, times(1)).sendLiveEvent(MESSAGE.userIds(asList("1", "2")));
  }

  @Test
  public void sendLiveDirectMessageEventToUsers_Should_doNothing_When_rcGroupIdIsEmpty() {
    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers("");

    verifyNoInteractions(userIdsProviderFactory);
    verifyNoInteractions(liveControllerApi);
    verifyNoInteractions(mobilePushNotificationService);
  }

  @Test
  public void sendLiveDirectMessageEventToUsers_Should_doNothing_When_rcGroupIdIsNull() {
    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers(null);

    verifyNoInteractions(userIdsProviderFactory);
    verifyNoInteractions(liveControllerApi);
  }

  @Test
  public void sendLiveDirectMessageEventToUsers_Should_logError_When_apiCallFails()
      throws ApiException {
    when(this.userIdsProviderFactory.byRocketChatGroup(any())).thenReturn(bySessionProvider);
    when(this.bySessionProvider.collectUserIds(any())).thenReturn(singletonList("test"));
    doThrow(new ApiException("")).when(this.liveControllerApi).sendLiveEvent(any());

    try (var logCaptor = LogbackCaptor.forClass(LiveEventNotificationService.class)) {
      this.liveEventNotificationService.sendLiveDirectMessageEventToUsers("group id");

      assertThat(logCaptor.contains(Level.ERROR, "Internal Server Error")).isTrue();
    }
  }

  @Test
  public void sendLiveDirectMessageEventToUsers_Should_sendEventToAllUsersInsteadOfInitiatingUser()
      throws ApiException {
    List<String> userIds = asList("id1", "id2", "id3", "id4");
    when(this.byChatProvider.collectUserIds(any())).thenReturn(userIds);
    when(this.userIdsProviderFactory.byRocketChatGroup(any())).thenReturn(this.byChatProvider);
    when(this.authenticatedUser.getUserId()).thenReturn("id2");

    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers("group id");

    List<String> expectedIds = asList("id1", "id3", "id4");
    verify(this.liveControllerApi, times(1)).sendLiveEvent(MESSAGE.userIds(expectedIds));
  }

  @Test
  public void sendLiveDirectMessageEventToUsers_Should_sendNothing_When_noIdsAreProvided() {
    when(this.userIdsProviderFactory.byRocketChatGroup(any())).thenReturn(this.byChatProvider);

    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers("group id");

    verifyNoInteractions(this.liveControllerApi);
  }

  @Test
  public void
      sendLiveDirectMessageEventToUsers_Should_sendEventToAllUsers_When_initiatingUserIsAnother()
          throws ApiException {
    List<String> userIds = asList("id1", "id2", "id3", "id4");
    when(this.byChatProvider.collectUserIds(any())).thenReturn(userIds);
    when(this.userIdsProviderFactory.byRocketChatGroup(any())).thenReturn(this.byChatProvider);
    when(this.authenticatedUser.getUserId()).thenReturn("another");

    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers("group id");

    verify(this.liveControllerApi, times(1)).sendLiveEvent(MESSAGE.userIds(userIds));
  }

  @Test
  public void sendLiveNewAnonymousEnquiryEventToUsers_Should_TriggerLiveEventWithCorrectEventType()
      throws ApiException {
    List<String> userIds = List.of("1", "2");

    this.liveEventNotificationService.sendLiveNewAnonymousEnquiryEventToUsers(userIds, 1L);

    ArgumentCaptor<LiveEventMessage> captor = ArgumentCaptor.forClass(LiveEventMessage.class);
    verify(liveControllerApi, times(1)).sendLiveEvent(captor.capture());
    assertEquals(EventType.NEW_ANONYMOUS_ENQUIRY, captor.getValue().getEventType());
  }

  @Test
  public void sendAcceptAnonymousEnquiryEventToUser_Should_doNothing_When_userIdIsNull() {
    this.liveEventNotificationService.sendAcceptAnonymousEnquiryEventToUser(null);

    verifyNoInteractions(this.liveControllerApi);
  }

  @Test
  public void sendAcceptAnonymousEnquiryEventToUser_Should_doNothing_When_userIdIsEmpty() {
    this.liveEventNotificationService.sendAcceptAnonymousEnquiryEventToUser("");

    verifyNoInteractions(this.liveControllerApi);
  }

  @Test
  public void sendAcceptAnonymousEnquiryEventToUser_Should_triggerLiveEvent_When_userIdIsValid()
      throws ApiException {
    this.liveEventNotificationService.sendAcceptAnonymousEnquiryEventToUser("userId");

    verify(this.liveControllerApi, times(1))
        .sendLiveEvent(
            new LiveEventMessage()
                .eventType(ANONYMOUS_ENQUIRY_ACCEPTED)
                .userIds(singletonList("userId")));
  }

  @Test
  public void sendLiveFinishedAnonymousConversationToUsers_Should_doNothing_When_userIdIsNull() {
    this.liveEventNotificationService.sendLiveFinishedAnonymousConversationToUsers(null, null);

    verifyNoInteractions(this.liveControllerApi);
  }

  @Test
  public void sendLiveFinishedAnonymousConversationToUsers_Should_doNothing_When_userIdIsEmpty() {
    this.liveEventNotificationService.sendLiveFinishedAnonymousConversationToUsers(
        emptyList(), null);

    verifyNoInteractions(this.liveControllerApi);
  }

  @Test
  public void
      sendLiveFinishedAnonymousConversationToUsers_Should_triggerLiveEvent_When_userIdIsValid()
          throws ApiException {
    this.liveEventNotificationService.sendLiveFinishedAnonymousConversationToUsers(
        singletonList("userId"), FinishConversationPhaseEnum.IN_PROGRESS);

    verify(this.liveControllerApi, times(1))
        .sendLiveEvent(
            new LiveEventMessage()
                .eventType(ANONYMOUS_CONVERSATION_FINISHED)
                .userIds(singletonList("userId"))
                .eventContent(
                    new StatusSource()
                        .finishConversationPhase(FinishConversationPhaseEnum.IN_PROGRESS)));
  }
}
