package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.matrix.RedisMessageMirrorService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.PrivacyEnvelope;
import de.caritas.cob.userservice.api.service.notification.TeamDiscussionNotificationService;
import jakarta.validation.constraints.Min;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class EventNotificationControllerTest {

  @Mock private EventNotificationService eventNotificationService;
  @Mock private TeamDiscussionNotificationService teamDiscussionNotificationService;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private RedisMessageMirrorService redisMessageMirrorService;

  private EventNotificationController controllerWithMirror;
  private EventNotificationController controllerWithoutMirror;

  @BeforeEach
  void setUp() {
    controllerWithMirror =
        new EventNotificationController(
            eventNotificationService,
            teamDiscussionNotificationService,
            authenticatedUser,
            Optional.of(redisMessageMirrorService));
    controllerWithoutMirror =
        new EventNotificationController(
            eventNotificationService,
            teamDiscussionNotificationService,
            authenticatedUser,
            Optional.empty());
  }

  @Test
  void getFeed_defaultPagePerPage_delegatesWithDefaults() {
    // Business reason: default feed pagination keeps consistent first-load UX across clients.
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    var feed =
        EventNotificationService.NotificationFeedResponse.builder()
            .items(List.of())
            .page(0)
            .perPage(50)
            .unreadCount(0)
            .build();
    when(eventNotificationService.getFeed("u-1", 0, 50)).thenReturn(feed);

    var response = controllerWithMirror.getFeed(0, 50);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(0, response.getBody().getPage());
    verify(eventNotificationService).getFeed("u-1", 0, 50);
  }

  @Test
  void getFeed_customPagePerPage_delegatesWithGivenValues() {
    // Business reason: consumers need deterministic pagination for infinite-scroll behavior.
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    when(eventNotificationService.getFeed("u-1", 3, 15))
        .thenReturn(
            EventNotificationService.NotificationFeedResponse.builder()
                .items(List.of())
                .page(3)
                .perPage(15)
                .unreadCount(0)
                .build());

    var response = controllerWithMirror.getFeed(3, 15);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(eventNotificationService).getFeed("u-1", 3, 15);
  }

  @Test
  void getFeed_parameterAnnotations_includeExpectedMinConstraints() throws Exception {
    // Business reason: min constraints guard against invalid paging values reaching service layer.
    Method method = EventNotificationController.class.getMethod("getFeed", int.class, int.class);
    Min pageMin = (Min) method.getParameters()[0].getAnnotations()[1];
    Min perPageMin = (Min) method.getParameters()[1].getAnnotations()[1];
    assertEquals(0, pageMin.value());
    assertEquals(1, perPageMin.value());
  }

  @Test
  void updateActiveView_allParamsPresent_delegatesCorrectly() {
    // Business reason: active-view state must be synchronized to avoid noisy notification delivery.
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    var request = new EventNotificationController.ActiveViewRequestDTO();
    request.setRoomId("room-1");
    request.setThreadRootId("thread-1");
    request.setActive(false);

    var response = controllerWithMirror.updateActiveView(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(eventNotificationService).updateActiveView("u-1", "room-1", "thread-1", false);
  }

  @Test
  void updateActiveView_allNulls_delegatesWithoutThrow() {
    // Business reason: null active-view payloads from stale clients should not break request flow.
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    var request = new EventNotificationController.ActiveViewRequestDTO();

    var response = controllerWithMirror.updateActiveView(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(eventNotificationService).updateActiveView("u-1", null, null, true);
  }

  @Test
  void createMessageEventNotification_blankRoomId_returnsBadRequest() {
    // Business reason: room id is required to route notification events safely.
    var request = new EventNotificationController.MessageEventRequestDTO();
    request.setRoomId("   ");

    var response = controllerWithMirror.createMessageEventNotification(request);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verify(eventNotificationService, never())
        .createMessageNotificationFromRoom(any(), any(), any(), anyBoolean(), any());
  }

  @Test
  void createMessageEventNotification_withoutThread_delegatesMessageNotification() {
    // Business reason: plain message events must route through non-thread notification flow.
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    when(authenticatedUser.getUsername()).thenReturn("consultant");
    when(authenticatedUser.getRoles()).thenReturn(Set.of("consultant"));
    var request = new EventNotificationController.MessageEventRequestDTO();
    request.setRoomId("room-2");
    request.setMessagePreview("hello");
    request.setSupervisorMessage(false);
    request.setSenderDisplayName("Sender");

    var response = controllerWithMirror.createMessageEventNotification(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(eventNotificationService)
        .createMessageNotificationFromRoom("room-2", "u-1", "hello", false, "Sender", null);
  }

  @Test
  void createMessageEventNotification_withThread_delegatesThreadReply() {
    // Business reason: thread replies must use dedicated event type to preserve client thread
    // context.
    when(authenticatedUser.getUserId()).thenReturn("u-2");
    when(authenticatedUser.getUsername()).thenReturn("consultant");
    when(authenticatedUser.getRoles()).thenReturn(Set.of("consultant"));
    var request = new EventNotificationController.MessageEventRequestDTO();
    request.setRoomId("room-3");
    request.setThreadRootId("thread-3");
    request.setMessagePreview("reply");
    request.setSupervisorMessage(true);
    request.setSenderDisplayName("Sender-3");
    request.setThreadParentPreview("parent");

    var response = controllerWithMirror.createMessageEventNotification(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(eventNotificationService)
        .createThreadReplyNotificationFromRoom(
            "room-3", "u-2", "reply", "thread-3", true, "Sender-3", "parent", null);
  }

  @Test
  void createMessageEventNotification_redisMirrorPresent_callsMirror() {
    // Business reason: mirrored outgoing previews support operational diagnostics for message
    // events.
    when(authenticatedUser.getUserId()).thenReturn("u-3");
    when(authenticatedUser.getUsername()).thenReturn("consultant");
    when(authenticatedUser.getRoles()).thenReturn(Set.of("consultant"));
    var request = new EventNotificationController.MessageEventRequestDTO();
    request.setRoomId("room-4");
    request.setMessagePreview("preview");

    controllerWithMirror.createMessageEventNotification(request);

    verify(redisMessageMirrorService)
        .mirrorOutgoingMessage(null, "room-4", "consultant", true, "preview", null);
  }

  @Test
  void createMessageEventNotification_redisMirrorAbsent_noMirrorCallAndNoThrow() {
    // Business reason: notification publishing must remain resilient even when mirror is disabled.
    when(authenticatedUser.getUserId()).thenReturn("u-4");
    var request = new EventNotificationController.MessageEventRequestDTO();
    request.setRoomId("room-5");
    request.setMessagePreview("preview");

    var response = controllerWithoutMirror.createMessageEventNotification(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(eventNotificationService)
        .createMessageNotificationFromRoom("room-5", "u-4", "preview", false, null, null);
  }

  @Test
  void createMessageEventNotification_withMatrixEventId_passesDedupEnvelope() {
    // #942: the Matrix event id keys deduplication against the sync listener.
    when(authenticatedUser.getUserId()).thenReturn("u-5");
    var request = new EventNotificationController.MessageEventRequestDTO();
    request.setRoomId("room-6");
    request.setMessagePreview("preview");
    request.setMatrixEventId("$evt-42");

    var response = controllerWithoutMirror.createMessageEventNotification(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    var envelopeCaptor = org.mockito.ArgumentCaptor.forClass(PrivacyEnvelope.class);
    verify(eventNotificationService)
        .createMessageNotificationFromRoom(
            org.mockito.ArgumentMatchers.eq("room-6"),
            org.mockito.ArgumentMatchers.eq("u-5"),
            org.mockito.ArgumentMatchers.eq("preview"),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.isNull(),
            envelopeCaptor.capture());
    assertEquals("$evt-42", envelopeCaptor.getValue().getMessageId());
  }

  @Test
  void createMessageEventNotification_withContentMetadata_populatesEnvelope() {
    // #942 review: the REST producer must carry contentClass/hasAttachment like the
    // Matrix listener, or the persisted row loses them when this path wins the dedup race.
    when(authenticatedUser.getUserId()).thenReturn("u-6");
    var request = new EventNotificationController.MessageEventRequestDTO();
    request.setRoomId("room-7");
    request.setMessagePreview("preview");
    request.setMatrixEventId("$evt-43");
    request.setContentClass("image");
    request.setHasAttachment(true);

    var response = controllerWithoutMirror.createMessageEventNotification(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    var envelopeCaptor = org.mockito.ArgumentCaptor.forClass(PrivacyEnvelope.class);
    verify(eventNotificationService)
        .createMessageNotificationFromRoom(
            org.mockito.ArgumentMatchers.eq("room-7"),
            org.mockito.ArgumentMatchers.eq("u-6"),
            org.mockito.ArgumentMatchers.eq("preview"),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.isNull(),
            envelopeCaptor.capture());
    assertEquals("IMAGE", envelopeCaptor.getValue().getContentClass());
    assertEquals(true, envelopeCaptor.getValue().isHasAttachment());
  }

  @Test
  void createMessageEventNotification_unknownContentClass_normalisedToOther() {
    // contentClass feeds text rendered for other users — arbitrary client strings
    // must collapse into the classifyContent vocabulary.
    when(authenticatedUser.getUserId()).thenReturn("u-6");
    var request = new EventNotificationController.MessageEventRequestDTO();
    request.setRoomId("room-7");
    request.setMatrixEventId("$evt-43");
    request.setContentClass("<script>alert(1)</script>");

    controllerWithoutMirror.createMessageEventNotification(request);

    var envelopeCaptor = org.mockito.ArgumentCaptor.forClass(PrivacyEnvelope.class);
    verify(eventNotificationService)
        .createMessageNotificationFromRoom(
            org.mockito.ArgumentMatchers.eq("room-7"),
            org.mockito.ArgumentMatchers.eq("u-6"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.isNull(),
            envelopeCaptor.capture());
    assertEquals("OTHER", envelopeCaptor.getValue().getContentClass());
  }

  @Test
  void createThreadReplyEventNotification_withMatrixEventId_passesDedupEnvelope() {
    // #942 review: the thread-reply branch shares the envelope construction and
    // must dedup by Matrix event id too.
    when(authenticatedUser.getUserId()).thenReturn("u-7");
    var request = new EventNotificationController.MessageEventRequestDTO();
    request.setRoomId("room-8");
    request.setMessagePreview("reply");
    request.setThreadRootId("$root-1");
    request.setMatrixEventId("$evt-44");

    var response = controllerWithoutMirror.createMessageEventNotification(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    var envelopeCaptor = org.mockito.ArgumentCaptor.forClass(PrivacyEnvelope.class);
    verify(eventNotificationService)
        .createThreadReplyNotificationFromRoom(
            org.mockito.ArgumentMatchers.eq("room-8"),
            org.mockito.ArgumentMatchers.eq("u-7"),
            org.mockito.ArgumentMatchers.eq("reply"),
            org.mockito.ArgumentMatchers.eq("$root-1"),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            envelopeCaptor.capture());
    assertEquals("$evt-44", envelopeCaptor.getValue().getMessageId());
  }

  @Test
  void createMessageEventNotification_matrixEventIdBoundedAndBodyValidated() throws Exception {
    // #942 review: oversized event ids must be rejected at the API boundary before
    // they can poison the 191-char dedup column.
    java.lang.reflect.Field field =
        EventNotificationController.MessageEventRequestDTO.class.getDeclaredField("matrixEventId");
    jakarta.validation.constraints.Size size =
        field.getAnnotation(jakarta.validation.constraints.Size.class);
    assertEquals(255, size.max());

    Method endpoint =
        EventNotificationController.class.getMethod(
            "createMessageEventNotification",
            EventNotificationController.MessageEventRequestDTO.class);
    assertEquals(
        true, endpoint.getParameters()[0].isAnnotationPresent(jakarta.validation.Valid.class));
  }
}
