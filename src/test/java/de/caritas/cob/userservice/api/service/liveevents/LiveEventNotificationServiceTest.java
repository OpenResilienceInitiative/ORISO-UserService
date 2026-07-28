package de.caritas.cob.userservice.api.service.liveevents;

import static de.caritas.cob.userservice.api.service.liveevents.LiveEvent.FinishConversationPhase.IN_PROGRESS;
import static de.caritas.cob.userservice.api.service.liveevents.LiveEvent.anonymousConversationFinished;
import static de.caritas.cob.userservice.api.service.liveevents.LiveEvent.anonymousEnquiryAccepted;
import static de.caritas.cob.userservice.api.service.liveevents.LiveEvent.directMessage;
import static de.caritas.cob.userservice.api.service.liveevents.LiveEvent.newAnonymousEnquiry;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.LiveEventGateway;
import de.caritas.cob.userservice.api.service.mobilepushmessage.MobilePushNotificationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class LiveEventNotificationServiceTest {

  @InjectMocks private LiveEventNotificationService liveEventNotificationService;

  @Mock private LiveEventGateway liveEventGateway;

  @Mock private UserIdsProviderFactory userIdsProviderFactory;

  @Mock private RelevantUserAccountIdsBySessionProvider bySessionProvider;

  @Mock private RelevantUserAccountIdsByChatProvider byChatProvider;

  @Mock private AuthenticatedUser authenticatedUser;

  @Mock private MobilePushNotificationService mobilePushNotificationService;

  @Test
  public void sendLiveDirectMessageEventToUsers_Should_callGateway_When_matrixRoomIdIsValid() {
    when(this.bySessionProvider.collectUserIds(any())).thenReturn(asList("1", "2"));
    when(this.userIdsProviderFactory.forMatrixRoom(any())).thenReturn(bySessionProvider);

    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers("valid");

    verify(userIdsProviderFactory, times(1)).forMatrixRoom("valid");
    verify(liveEventGateway, times(1)).send(directMessage(asList("1", "2")));
  }

  @Test
  public void sendLiveDirectMessageEventToUsers_Should_doNothing_When_matrixRoomIdIsEmpty() {
    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers("");

    verifyNoInteractions(userIdsProviderFactory);
    verifyNoInteractions(liveEventGateway);
    verifyNoInteractions(mobilePushNotificationService);
  }

  @Test
  public void sendLiveDirectMessageEventToUsers_Should_doNothing_When_matrixRoomIdIsNull() {
    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers(null);

    verifyNoInteractions(userIdsProviderFactory);
    verifyNoInteractions(liveEventGateway);
  }

  @Test
  public void
      sendLiveDirectMessageEventToUsers_Should_sendEventToAllUsersInsteadOfInitiatingUser() {
    List<String> userIds = asList("id1", "id2", "id3", "id4");
    when(this.byChatProvider.collectUserIds(any())).thenReturn(userIds);
    when(this.userIdsProviderFactory.forMatrixRoom(any())).thenReturn(this.byChatProvider);
    when(this.authenticatedUser.getUserId()).thenReturn("id2");

    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers("group id");

    List<String> expectedIds = asList("id1", "id3", "id4");
    verify(this.liveEventGateway, times(1)).send(directMessage(expectedIds));
  }

  @Test
  public void
      sendLiveDirectMessageEventToUsers_Should_sendToAllUsers_When_noRequestContextIsBound() {
    // Called from the Matrix sync-loop background thread there is no web request, so the
    // request-scoped AuthenticatedUser proxy throws. The event must still go out (to
    // everyone — without an initiator there is nobody to exclude).
    List<String> userIds = asList("id1", "id2");
    when(this.byChatProvider.collectUserIds(any())).thenReturn(userIds);
    when(this.userIdsProviderFactory.forMatrixRoom(any())).thenReturn(this.byChatProvider);
    when(this.authenticatedUser.getUserId())
        .thenThrow(new IllegalStateException("No thread-bound request found"));

    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers("group id");

    verify(this.liveEventGateway, times(1)).send(directMessage(userIds));
  }

  @Test
  public void sendLiveDirectMessageEventToUsers_Should_sendNothing_When_noIdsAreProvided() {
    when(this.userIdsProviderFactory.forMatrixRoom(any())).thenReturn(this.byChatProvider);

    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers("group id");

    verifyNoInteractions(this.liveEventGateway);
  }

  @Test
  public void
      sendLiveDirectMessageEventToUsers_Should_sendEventToAllUsers_When_initiatingUserIsAnother() {
    List<String> userIds = asList("id1", "id2", "id3", "id4");
    when(this.byChatProvider.collectUserIds(any())).thenReturn(userIds);
    when(this.userIdsProviderFactory.forMatrixRoom(any())).thenReturn(this.byChatProvider);
    when(this.authenticatedUser.getUserId()).thenReturn("another");

    this.liveEventNotificationService.sendLiveDirectMessageEventToUsers("group id");

    verify(this.liveEventGateway, times(1)).send(directMessage(userIds));
  }

  @Test
  public void
      sendLiveNewAnonymousEnquiryEventToUsers_Should_TriggerLiveEventWithCorrectEventType() {
    List<String> userIds = List.of("1", "2");

    this.liveEventNotificationService.sendLiveNewAnonymousEnquiryEventToUsers(userIds, 1L);

    verify(liveEventGateway, times(1)).send(newAnonymousEnquiry(userIds));
  }

  // WP-06 Activity Timeline: single-recipient live refresh nudge fired when an event_notification
  // is persisted, so the Activity Timeline updates in real time instead of on the slow fallback
  // poll. Reuses DIRECT_MESSAGE (no new live-service enum), carries only the recipient user id.
  @Test
  public void sendEventNotificationCreatedEventToUser_Should_sendDirectMessageEventForRecipient() {
    this.liveEventNotificationService.sendEventNotificationCreatedEventToUser("recipient-1");

    verify(this.liveEventGateway, times(1)).send(directMessage(singletonList("recipient-1")));
  }

  @Test
  public void sendEventNotificationCreatedEventToUser_Should_doNothing_When_userIdIsBlank() {
    this.liveEventNotificationService.sendEventNotificationCreatedEventToUser("  ");

    verifyNoInteractions(this.liveEventGateway);
  }

  @Test
  public void sendEventNotificationCreatedEventToUser_Should_doNothing_When_userIdIsNull() {
    this.liveEventNotificationService.sendEventNotificationCreatedEventToUser(null);

    verifyNoInteractions(this.liveEventGateway);
  }

  @Test
  public void sendAcceptAnonymousEnquiryEventToUser_Should_doNothing_When_userIdIsNull() {
    this.liveEventNotificationService.sendAcceptAnonymousEnquiryEventToUser(null);

    verifyNoInteractions(this.liveEventGateway);
  }

  @Test
  public void sendAcceptAnonymousEnquiryEventToUser_Should_doNothing_When_userIdIsEmpty() {
    this.liveEventNotificationService.sendAcceptAnonymousEnquiryEventToUser("");

    verifyNoInteractions(this.liveEventGateway);
  }

  @Test
  public void sendAcceptAnonymousEnquiryEventToUser_Should_triggerLiveEvent_When_userIdIsValid() {
    this.liveEventNotificationService.sendAcceptAnonymousEnquiryEventToUser("userId");

    verify(this.liveEventGateway, times(1)).send(anonymousEnquiryAccepted(singletonList("userId")));
  }

  @Test
  public void sendLiveFinishedAnonymousConversationToUsers_Should_doNothing_When_userIdIsNull() {
    this.liveEventNotificationService.sendLiveFinishedAnonymousConversationToUsers(null, null);

    verifyNoInteractions(this.liveEventGateway);
  }

  @Test
  public void sendLiveFinishedAnonymousConversationToUsers_Should_doNothing_When_userIdIsEmpty() {
    this.liveEventNotificationService.sendLiveFinishedAnonymousConversationToUsers(
        emptyList(), null);

    verifyNoInteractions(this.liveEventGateway);
  }

  @Test
  public void
      sendLiveFinishedAnonymousConversationToUsers_Should_triggerLiveEvent_When_userIdIsValid() {
    this.liveEventNotificationService.sendLiveFinishedAnonymousConversationToUsers(
        singletonList("userId"), IN_PROGRESS);

    verify(this.liveEventGateway, times(1))
        .send(anonymousConversationFinished(singletonList("userId"), IN_PROGRESS));
  }
}
