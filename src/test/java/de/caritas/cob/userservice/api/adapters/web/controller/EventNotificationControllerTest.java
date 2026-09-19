package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.RedisMessageMirrorService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationDeduplicationWriter;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.PrivacyEnvelope;
import de.caritas.cob.userservice.api.service.notification.TeamDiscussionNotificationService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import jakarta.validation.constraints.Min;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

  // ---------------------------------------------------------------------------
  // ADR-002 §2 / #1201: the client posts the sender's name in the request body. The server must
  // not repeat it to a third party -- it knows who the sender is (senderUserId comes from the
  // authenticated principal, not from the body) and resolves the name itself.
  // ---------------------------------------------------------------------------

  @Test
  void createMessageEventNotification_Should_NotLetTheClientNameTheSenderToTheAdviceSeeker() {
    var eventNotificationRepository = mock(EventNotificationRepository.class);
    var sessionRepository = mock(SessionRepository.class);
    var userRepository = mock(UserRepository.class);
    var consultantRepository = mock(ConsultantRepository.class);
    var identityTombstoneService = mock(IdentityTombstoneService.class);
    var deduplicationWriter = mock(EventNotificationDeduplicationWriter.class);
    var realService =
        new EventNotificationService(
            eventNotificationRepository,
            sessionRepository,
            userRepository,
            consultantRepository,
            identityTombstoneService,
            deduplicationWriter,
            new ConsultantDisplayNameResolver());
    var controller =
        new EventNotificationController(
            realService, teamDiscussionNotificationService, authenticatedUser, Optional.empty());

    when(authenticatedUser.getUserId()).thenReturn("counsellor-1");
    when(consultantRepository.findByIdAndDeleteDateIsNull("counsellor-1"))
        .thenReturn(
            Optional.of(
                Consultant.builder()
                    .id("counsellor-1")
                    .username("beraterin1")
                    .firstName("Angela")
                    .lastName("Musterfrau")
                    .displayName(null)
                    .email("angela@example.org")
                    .build()));
    var adviceSeeker = mock(User.class);
    when(adviceSeeker.getUserId()).thenReturn("asker-1");
    var session = mock(Session.class);
    when(session.getUser()).thenReturn(adviceSeeker);
    when(session.getId()).thenReturn(100L);
    when(sessionRepository.findByMatrixRoomId("!room-1:matrix.example"))
        .thenReturn(Optional.of(session));

    var request = new EventNotificationController.MessageEventRequestDTO();
    request.setRoomId("!room-1:matrix.example");
    request.setMessagePreview("hello");
    // The lie: a client is free to put anything here, and the frontend in fact falls back to the
    // real name when the counsellor has no pseudonym.
    request.setSenderDisplayName("Angela Musterfrau");

    controller.createMessageEventNotification(request);

    var saved = ArgumentCaptor.forClass(EventNotification.class);
    verify(eventNotificationRepository, atLeastOnce()).save(saved.capture());
    assertThat(saved.getAllValues())
        .isNotEmpty()
        .allSatisfy(
            row -> {
              assertThat(row.getText()).doesNotContain("Angela", "Musterfrau");
              assertThat(row.getParams()).doesNotContain("Angela", "Musterfrau");
            });
    assertThat(saved.getAllValues())
        .anySatisfy(row -> assertThat(row.getText()).contains("beraterin1"));
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
    when(eventNotificationService.getFeed("u-1", 0, 50, Set.of())).thenReturn(feed);

    var response = controllerWithMirror.getFeed(0, 50, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(0, response.getBody().getPage());
    verify(eventNotificationService).getFeed("u-1", 0, 50, Set.of());
  }

  @Test
  void getFeed_customPagePerPage_delegatesWithGivenValues() {
    // Business reason: consumers need deterministic pagination for infinite-scroll behavior.
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    when(eventNotificationService.getFeed("u-1", 3, 15, Set.of()))
        .thenReturn(
            EventNotificationService.NotificationFeedResponse.builder()
                .items(List.of())
                .page(3)
                .perPage(15)
                .unreadCount(0)
                .build());

    var response = controllerWithMirror.getFeed(3, 15, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(eventNotificationService).getFeed("u-1", 3, 15, Set.of());
  }

  @Test
  void getFeed_parameterAnnotations_includeExpectedMinConstraints() throws Exception {
    // Business reason: min constraints guard against invalid paging values reaching service layer.
    Method method =
        EventNotificationController.class.getMethod("getFeed", int.class, int.class, String.class);
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
        .createMessageNotificationFromRoom("room-2", "u-1", "hello", false, (PrivacyEnvelope) null);
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
            "room-3", "u-2", "reply", "thread-3", true, "parent", null);
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
        .createMessageNotificationFromRoom(
            "room-5", "u-4", "preview", false, (PrivacyEnvelope) null);
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

  // ---------------------------------------------------------------------------
  // #1377 slice 7 — exclusions and bulk read by event type
  // ---------------------------------------------------------------------------

  @Test
  void getFeed_withExcludeEventTypes_passesParsedSetToService() {
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    when(eventNotificationService.getFeed("u-1", 0, 50, Set.of("supervisor.added", "call.missed")))
        .thenReturn(
            EventNotificationService.NotificationFeedResponse.builder()
                .items(List.of())
                .page(0)
                .perPage(50)
                .unreadCount(3)
                .excludedEventTypes(List.of("call.missed", "supervisor.added"))
                .build());

    var response = controllerWithMirror.getFeed(0, 50, " supervisor.added, call.missed ,,");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(3, response.getBody().getUnreadCount());
    assertEquals(
        List.of("call.missed", "supervisor.added"), response.getBody().getExcludedEventTypes());
  }

  @Test
  void getFeed_withTooManyExcludeEventTypes_returnsBadRequest() {
    String tooMany =
        java.util.stream.IntStream.rangeClosed(1, EventNotificationController.MAX_EVENT_TYPES + 1)
            .mapToObj(i -> "type." + i)
            .reduce((a, b) -> a + "," + b)
            .orElseThrow();

    var response = controllerWithMirror.getFeed(0, 50, tooMany);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verify(eventNotificationService, never())
        .getFeed(any(), any(Integer.class), any(Integer.class), any());
  }

  @Test
  void getUnreadCount_delegatesWithExclusionsAndEchoesThem() {
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    when(eventNotificationService.countUnread("u-1", Set.of("call.missed"))).thenReturn(7L);

    var response = controllerWithMirror.getUnreadCount("call.missed");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(7L, response.getBody().getUnreadCount());
    assertEquals(List.of("call.missed"), response.getBody().getExcludedEventTypes());
  }

  @Test
  void getUnreadCount_overlongEventType_returnsBadRequest() {
    var response =
        controllerWithMirror.getUnreadCount(
            "x".repeat(EventNotificationController.MAX_EVENT_TYPE_LENGTH + 1));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verify(eventNotificationService, never()).countUnread(any(), any());
  }

  @Test
  void markAsReadByEventTypes_delegatesAndReportsCount() {
    when(authenticatedUser.getUserId()).thenReturn("u-1");
    when(eventNotificationService.markAsReadByEventTypes(
            "u-1", Set.of("supervisor.added", "supervisor.removed")))
        .thenReturn(12);

    var response =
        controllerWithMirror.markAsReadByEventTypes("supervisor.added,supervisor.removed");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(12, response.getBody().getUpdated());
    assertEquals(
        List.of("supervisor.added", "supervisor.removed"), response.getBody().getEventTypes());
  }

  @Test
  void markAsReadByEventTypes_emptyList_isBadRequestNotReadAll() {
    // Business reason: a missing list must never degrade into "mark everything read".
    assertEquals(
        HttpStatus.BAD_REQUEST, controllerWithMirror.markAsReadByEventTypes(null).getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST, controllerWithMirror.markAsReadByEventTypes(" , ").getStatusCode());
    verify(eventNotificationService, never()).markAsReadByEventTypes(any(), any());
    verify(eventNotificationService, never()).markAllAsRead(any());
  }
}
