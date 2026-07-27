package de.caritas.cob.userservice.api.service.liveevents;

import static de.caritas.cob.userservice.api.service.liveevents.LiveEvent.anonymousConversationFinished;
import static de.caritas.cob.userservice.api.service.liveevents.LiveEvent.anonymousEnquiryAccepted;
import static de.caritas.cob.userservice.api.service.liveevents.LiveEvent.directMessage;
import static de.caritas.cob.userservice.api.service.liveevents.LiveEvent.newAnonymousEnquiry;
import static java.util.Collections.singletonList;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.LiveEventGateway;
import de.caritas.cob.userservice.api.service.liveevents.LiveEvent.FinishConversationPhase;
import de.caritas.cob.userservice.api.service.mobilepushmessage.MobilePushNotificationService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Service class to provide live event triggers to the live service. */
@Service
@RequiredArgsConstructor
public class LiveEventNotificationService {

  private final @NonNull LiveEventGateway liveEventGateway;
  private final @NonNull UserIdsProviderFactory userIdsProviderFactory;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull MobilePushNotificationService mobilePushNotificationService;

  /**
   * Sends a anonymous enquiry accepted event to the live service,
   *
   * @param userId the id of the user who should receive the event
   */
  public void sendAcceptAnonymousEnquiryEventToUser(String userId) {
    if (isNotBlank(userId)) {
      liveEventGateway.send(anonymousEnquiryAccepted(singletonList(userId)));
    }
  }

  /**
   * WP-06 Activity Timeline: nudge a single recipient's client to refresh its notification feed the
   * moment a timeline {@code event_notification} is persisted, so the Activity Timeline updates in
   * real time instead of waiting for the slow fallback poll.
   *
   * <p>Reuses the {@code DIRECT_MESSAGE} event type the client already listens for — this avoids
   * coupling a new live-service enum value across the separate live-service deployment — and
   * carries only the recipient user id, never any notification content, keeping the privacy
   * boundary intact (ADR-AT-01 / FE-H01). Best-effort like every live event.
   *
   * @param userId the recipient whose client should refresh its Activity Timeline
   */
  public void sendEventNotificationCreatedEventToUser(String userId) {
    if (isNotBlank(userId)) {
      liveEventGateway.send(directMessage(singletonList(userId)));
    }
  }

  /**
   * Collects all relevant user or consultant ids of chats and sessions and sends a new direct
   * message to the live service.
   *
   * @param matrixRoomId Matrix room ID used to observe relevant users
   */
  public void sendLiveDirectMessageEventToUsers(String matrixRoomId) {
    if (isNotBlank(matrixRoomId)) {
      var userIds =
          this.userIdsProviderFactory
              .forMatrixRoom(matrixRoomId)
              .collectUserIds(matrixRoomId)
              .stream()
              .filter(this::notInitiatingUser)
              .collect(Collectors.toList());

      triggerDirectMessageLiveEvent(userIds, matrixRoomId);
      this.mobilePushNotificationService.triggerMobilePushNotification(userIds);
    }
  }

  private boolean notInitiatingUser(String userId) {
    try {
      return !userId.equals(this.authenticatedUser.getUserId());
    } catch (RuntimeException noRequestContext) {
      // AuthenticatedUser is request-scoped; on background threads (e.g. the Matrix
      // sync loop) there is no web request and thus no initiator to exclude.
      return true;
    }
  }

  private void triggerDirectMessageLiveEvent(List<String> userIds, String matrixRoomId) {
    if (isNotEmpty(userIds)) {
      liveEventGateway.send(directMessage(userIds));
    }
  }

  /**
   * Sends a new anonymous enquiry live event to the provided user IDs.
   *
   * @param userIds list of consultant user IDs
   * @param sessionId anonymous enquiry ID
   */
  public void sendLiveNewAnonymousEnquiryEventToUsers(List<String> userIds, Long sessionId) {
    if (isNotEmpty(userIds)) {
      liveEventGateway.send(newAnonymousEnquiry(userIds));
    }
  }

  /**
   * Sends a anonymous conversation finished live event to the provided user IDs.
   *
   * @param userIds list of consultant user IDs
   */
  public void sendLiveFinishedAnonymousConversationToUsers(
      List<String> userIds, FinishConversationPhase finishConversationPhase) {
    if (isNotEmpty(userIds)) {
      liveEventGateway.send(anonymousConversationFinished(userIds, finishConversationPhase));
    }
  }
}
