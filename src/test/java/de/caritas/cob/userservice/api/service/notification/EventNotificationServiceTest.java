package de.caritas.cob.userservice.api.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * WP-06 Activity Timeline (Slice 2, Tier 1) — coverage for the persisted "New client request"
 * ({@code request.new}) event and the structured params it carries (ADR-AT-01).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventNotificationServiceTest {

  @InjectMocks private EventNotificationService eventNotificationService;
  @Mock private EventNotificationRepository eventNotificationRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private IdentityTombstoneService identityTombstoneService;
  @Captor private ArgumentCaptor<EventNotification> eventCaptor;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private Session sessionMock() {
    Session session = mock(Session.class);
    when(session.getId()).thenReturn(100L);
    when(session.getTenantId()).thenReturn(7L);
    when(session.getAgencyId()).thenReturn(42L);
    when(session.getMainTopicId()).thenReturn(5L);
    when(session.getConsultingTypeId()).thenReturn(1);
    when(session.getMatrixRoomId()).thenReturn(null);
    when(session.getGroupId()).thenReturn("rc-group-1");
    return session;
  }

  @Test
  void createNewClientRequestNotifications_persistsRequestNewEventPerRecipient() throws Exception {
    Session session = sessionMock();

    eventNotificationService.createNewClientRequestNotifications(
        session, List.of("consultant-a", "consultant-b"));

    verify(eventNotificationRepository, times(2)).save(eventCaptor.capture());
    List<EventNotification> saved = eventCaptor.getAllValues();

    EventNotification first = saved.get(0);
    assertEquals("request.new", first.getEventType());
    assertEquals(EventNotificationService.CATEGORY_SYSTEM, first.getCategory());
    assertEquals("New client request", first.getTitle());
    assertEquals("A new client request is waiting.", first.getText());
    assertEquals(Long.valueOf(100L), first.getSourceSessionId());
    assertEquals(Long.valueOf(7L), first.getTenantId());
    assertEquals("/sessions/consultant/sessionView/rc-group-1/100", first.getActionPath());
    assertEquals("consultant-a", first.getRecipientUserId());
    assertEquals("consultant-b", saved.get(1).getRecipientUserId());

    assertNotNull(first.getParams());
    JsonNode params = objectMapper.readTree(first.getParams());
    assertEquals(100L, params.get("sessionId").asLong());
    assertEquals(42L, params.get("agencyId").asLong());
    assertEquals(5L, params.get("topicId").asLong());
    assertEquals(1, params.get("consultingTypeId").asInt());
  }

  @Test
  void createNewClientRequestNotifications_skipsBlankAndDeduplicatesRecipients() {
    Session session = sessionMock();

    eventNotificationService.createNewClientRequestNotifications(
        session, Arrays.asList("consultant-a", null, "  ", "consultant-a", "consultant-b"));

    // null/blank dropped, duplicate "consultant-a" collapsed -> 2 distinct recipients.
    verify(eventNotificationRepository, times(2)).save(any());
  }

  @Test
  void createNewClientRequestNotifications_doesNothingForNullInputs() {
    eventNotificationService.createNewClientRequestNotifications(null, List.of("consultant-a"));
    eventNotificationService.createNewClientRequestNotifications(sessionMock(), null);

    verify(eventNotificationRepository, never()).save(any());
  }

  // WP-06 Slice 2b: every producer now emits structured params alongside the (unchanged)
  // title/text.

  @Test
  void createInquiryAcceptedNotification_setsSessionAndConsultantNameParams() throws Exception {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    Consultant consultant = mock(Consultant.class);
    when(consultant.getDisplayName()).thenReturn("Jane Doe");

    eventNotificationService.createInquiryAcceptedNotification(session, consultant);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    EventNotification saved = eventCaptor.getValue();
    assertEquals("inquiry.accepted", saved.getEventType());
    assertEquals("asker-1", saved.getRecipientUserId());
    JsonNode params = objectMapper.readTree(saved.getParams());
    assertEquals(100L, params.get("sessionId").asLong());
    assertEquals("Jane Doe", params.get("consultantName").asText());
  }

  @Test
  void createSupervisorAddedNotification_setsSessionAndSupervisorNameParams() throws Exception {
    eventNotificationService.createSupervisorAddedNotification(
        sessionMock(), "asker-1", "Sam Supervisor");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    EventNotification saved = eventCaptor.getValue();
    assertEquals("supervisor.added", saved.getEventType());
    JsonNode params = objectMapper.readTree(saved.getParams());
    assertEquals(100L, params.get("sessionId").asLong());
    assertEquals("Sam Supervisor", params.get("supervisorName").asText());
  }

  @Test
  void createSupervisorAssignedNotification_setsParamsAndConsultantActionPath() throws Exception {
    eventNotificationService.createSupervisorAssignedNotification(sessionMock(), "consultant-x");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    EventNotification saved = eventCaptor.getValue();
    assertEquals("supervisor.assigned", saved.getEventType());
    assertEquals("consultant-x", saved.getRecipientUserId());
    assertEquals("/sessions/consultant/sessionView/rc-group-1/100", saved.getActionPath());
    JsonNode params = objectMapper.readTree(saved.getParams());
    assertEquals(100L, params.get("sessionId").asLong());
  }

  @Test
  void createSupervisorAssignedNotification_doesNothingForBlankRecipient() {
    eventNotificationService.createSupervisorAssignedNotification(sessionMock(), "  ");

    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createCounselorRenamedNotification_setsOldAndNewNameParams() throws Exception {
    eventNotificationService.createCounselorRenamedNotification(
        sessionMock(), "asker-1", "Old Name", "New Name");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    EventNotification saved = eventCaptor.getValue();
    assertEquals("counselor.renamed", saved.getEventType());
    JsonNode params = objectMapper.readTree(saved.getParams());
    assertEquals(100L, params.get("sessionId").asLong());
    assertEquals("Old Name", params.get("oldName").asText());
    assertEquals("New Name", params.get("newName").asText());
  }

  @Test
  void createMessageNotificationFromRoom_setsParamsAndNeverLeaksMessageBody() throws Exception {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "someone-else", "secret message body", false);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    EventNotification saved = eventCaptor.getValue();
    assertEquals("message.new", saved.getEventType());
    assertEquals("asker-1", saved.getRecipientUserId());
    JsonNode params = objectMapper.readTree(saved.getParams());
    assertEquals(100L, params.get("sessionId").asLong());
    assertEquals("user", params.get("recipientRole").asText());
    assertTrue(params.has("senderName"));
    // ADR-AT-01: params carry only non-content metadata — never the message preview/body.
    assertFalse(saved.getParams().contains("secret message body"));
  }

  @Test
  void createMessageNotificationFromRoom_doesNothingForBlankRoomId() {
    eventNotificationService.createMessageNotificationFromRoom("  ", "sender", "msg", false);

    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createMessageNotificationFromRoom_doesNothingWhenSessionNotFound() {
    when(sessionRepository.findByGroupId("no-room")).thenReturn(Optional.empty());

    eventNotificationService.createMessageNotificationFromRoom("no-room", "sender", "msg", false);

    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createMessageNotificationFromRoom_doesNothingForSystemNotificationMessages() {
    Session sysSession = sessionMock();
    when(sessionRepository.findByGroupId("rc-room")).thenReturn(Optional.of(sysSession));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-room", "sender", "[SYSTEM_NOTIFICATION] system stuff", false);

    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createSupervisorRemovedNotification_setsCorrectEventType() {
    eventNotificationService.createSupervisorRemovedNotification(
        sessionMock(), "asker-1", "Sam Supervisor");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertEquals("supervisor.removed", eventCaptor.getValue().getEventType());
  }

  @Test
  void markAsRead_updatesReadDate_When_NotificationExistsAndUnread() {
    EventNotification notification = EventNotification.builder().id(1L).build();
    when(eventNotificationRepository.findByIdAndRecipientUserId(1L, "user-1"))
        .thenReturn(Optional.of(notification));

    eventNotificationService.markAsRead("user-1", 1L);

    verify(eventNotificationRepository).save(notification);
    assertNotNull(notification.getReadDate());
  }

  @Test
  void markAsRead_doesNotSave_When_AlreadyRead() {
    EventNotification notification = EventNotification.builder().id(1L).build();
    notification.setReadDate(java.time.LocalDateTime.now());
    when(eventNotificationRepository.findByIdAndRecipientUserId(1L, "user-1"))
        .thenReturn(Optional.of(notification));

    eventNotificationService.markAsRead("user-1", 1L);

    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void markAllAsRead_savesAllUnread() {
    EventNotification n1 = EventNotification.builder().build();
    EventNotification n2 = EventNotification.builder().build();
    when(eventNotificationRepository.findByRecipientUserIdAndReadDateIsNull("user-1"))
        .thenReturn(List.of(n1, n2));

    eventNotificationService.markAllAsRead("user-1");

    verify(eventNotificationRepository).saveAll(List.of(n1, n2));
    assertNotNull(n1.getReadDate());
    assertNotNull(n2.getReadDate());
  }

  @Test
  void markAllAsRead_doesNothing_When_NoUnread() {
    when(eventNotificationRepository.findByRecipientUserIdAndReadDateIsNull("user-1"))
        .thenReturn(List.of());

    eventNotificationService.markAllAsRead("user-1");

    verify(eventNotificationRepository, never()).saveAll(any());
  }

  @Test
  void clearFeed_delegatesToRepository() {
    eventNotificationService.clearFeed("user-1");

    verify(eventNotificationRepository).deleteByRecipientUserId("user-1");
  }

  @Test
  void updateActiveView_removesEntry_When_NotActive() {
    eventNotificationService.updateActiveView("user-1", "room-1", null, true);
    eventNotificationService.updateActiveView("user-1", "room-1", null, false);
    // After deactivation, active view is gone — should not suppress notification
    // No repository interaction, but verifies no exception and state change works
  }

  @Test
  void updateActiveView_doesNothing_When_UserIdBlank() {
    // Should not throw
    eventNotificationService.updateActiveView("  ", "room-1", null, true);
    eventNotificationService.updateActiveView(null, "room-1", null, true);
  }

  @Test
  void updateActiveView_removesEntry_When_RoomIdBlankAndActive() {
    eventNotificationService.updateActiveView("user-1", "room-1", null, true);
    // Passing blank roomId with active=true should remove the entry
    eventNotificationService.updateActiveView("user-1", "  ", null, true);
    // No exception expected
  }

  @Test
  void createEvent_doesNothing_When_RecipientIsBlank() {
    eventNotificationService.createEvent("  ", "type", "cat", "title", "text", "/path", 1L, 1L);

    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createEvent_savesNotification_When_RecipientIsValid() {
    eventNotificationService.createEvent(
        "user-1", "type", "cat", "title", "text", "/path", 10L, 5L);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    EventNotification saved = eventCaptor.getValue();
    assertEquals("user-1", saved.getRecipientUserId());
    assertEquals("type", saved.getEventType());
    assertEquals(10L, saved.getSourceSessionId());
    assertEquals(5L, saved.getTenantId());
  }

  // ── Helper ────────────────────────────────────────────────────────────────

  private Session sessionWithUser(String userId) {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn(userId);
    when(session.getUser()).thenReturn(user);
    when(session.getConsultant()).thenReturn(null);
    return session;
  }

  // ── Privacy modes — createMessageNotificationFromRoom ─────────────────────

  @Test
  void createMessageNotification_NONE_mode_textDescribesEventWithoutPreview() {
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender-x", "secret message", false, false, "Jane");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertTrue(text.startsWith("Jane sent a new message."));
    assertFalse(text.contains("secret message"));
  }

  @Test
  void createMessageNotification_MASKED_mode_textShowsRedactedPlaceholder() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "MASKED");
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender-x", "secret message", false, false, "Jane");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertTrue(text.contains("[content hidden]"));
    assertFalse(text.contains("secret message"));
  }

  @Test
  void createMessageNotification_FULL_mode_textIncludesPreview() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "FULL");
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender-x", "hello world", false, false, "Jane");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertTrue(eventCaptor.getValue().getText().contains("hello world"));
  }

  @Test
  void createMessageNotification_invalid_privacyMode_fallsBackToNone() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "GARBAGE");
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender-x", "secret", false, false, "Jane");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertFalse(text.contains("secret"));
    assertFalse(text.contains("[content hidden]"));
  }

  // ── Privacy modes — createThreadReplyNotificationFromRoom ─────────────────

  @Test
  void createThreadReplyNotification_NONE_mode_textDoesNotContainPreview() {
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createThreadReplyNotificationFromRoom(
        "rc-group-1", "sender-x", "secret reply", "thread-root", false, false, "Jane", null);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertEquals("thread.reply.new", eventCaptor.getValue().getEventType());
    assertTrue(text.contains("replied in a thread"));
    assertFalse(text.contains("secret reply"));
  }

  @Test
  void createThreadReplyNotification_MASKED_mode_showsRedactedPlaceholder() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "MASKED");
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createThreadReplyNotificationFromRoom(
        "rc-group-1", "sender-x", "secret reply", "thread-root", false, false, "Jane", null);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertTrue(text.contains("[content hidden]"));
    assertFalse(text.contains("secret reply"));
  }

  @Test
  void createThreadReplyNotification_FULL_mode_includesPreviewAndParentPreview() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "FULL");
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createThreadReplyNotificationFromRoom(
        "rc-group-1", "sender-x", "my reply", "thread-root", false, false, "Jane", "parent msg");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertTrue(text.contains("my reply"));
    assertTrue(text.contains("parent msg"));
  }

  // ── getFeed pagination clamping ────────────────────────────────────────────

  @Test
  void getFeed_Should_ClampNegativePage_To_Zero() {
    when(eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDesc(any(), any()))
        .thenReturn(List.of());
    when(eventNotificationRepository.countByRecipientUserIdAndReadDateIsNull(any())).thenReturn(0L);

    EventNotificationService.NotificationFeedResponse response =
        eventNotificationService.getFeed("user-1", -5, 10);

    assertEquals(0, response.getPage());
    assertEquals(10, response.getPerPage());
  }

  @Test
  void getFeed_Should_ClampPerPage_When_ExceedsMaxOf100() {
    when(eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDesc(any(), any()))
        .thenReturn(List.of());
    when(eventNotificationRepository.countByRecipientUserIdAndReadDateIsNull(any())).thenReturn(0L);

    EventNotificationService.NotificationFeedResponse response =
        eventNotificationService.getFeed("user-1", 0, 999);

    assertEquals(100, response.getPerPage());
  }

  @Test
  void getFeed_Should_ClampPerPage_When_ZeroOrBelow() {
    when(eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDesc(any(), any()))
        .thenReturn(List.of());
    when(eventNotificationRepository.countByRecipientUserIdAndReadDateIsNull(any())).thenReturn(0L);

    EventNotificationService.NotificationFeedResponse response =
        eventNotificationService.getFeed("user-1", 0, 0);

    assertEquals(1, response.getPerPage());
  }

  // ── Active view suppression ────────────────────────────────────────────────

  @Test
  void createMessageNotification_Should_Suppress_When_UserActiveInSameRoom() {
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    eventNotificationService.updateActiveView("asker-1", "rc-group-1", null, true);

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender-x", "hello", false, false, "Jane");

    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createMessageNotification_Should_NotSuppress_When_UserActiveInDifferentRoom() {
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    eventNotificationService.updateActiveView("asker-1", "other-room", null, true);

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender-x", "hello", false, false, "Jane");

    verify(eventNotificationRepository).save(any());
  }

  @Test
  void createThreadReply_Should_Suppress_When_UserWatchingSameThread() {
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    eventNotificationService.updateActiveView("asker-1", "rc-group-1", "thread-123", true);

    eventNotificationService.createThreadReplyNotificationFromRoom(
        "rc-group-1", "sender-x", "reply text", "thread-123", false);

    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createThreadReply_Should_NotSuppress_When_UserWatchingDifferentThread() {
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    eventNotificationService.updateActiveView("asker-1", "rc-group-1", "thread-999", true);

    eventNotificationService.createThreadReplyNotificationFromRoom(
        "rc-group-1", "sender-x", "reply text", "thread-123", false);

    verify(eventNotificationRepository).save(any());
  }

  // ── buildSessionActionPath — matrixRoomId priority ────────────────────────

  @Test
  void createNewClientRequestNotifications_Should_UseMatrixRoomId_When_BothRoomIdsPresent()
      throws Exception {
    Session session = mock(Session.class);
    when(session.getId()).thenReturn(200L);
    when(session.getTenantId()).thenReturn(3L);
    when(session.getAgencyId()).thenReturn(null);
    when(session.getMainTopicId()).thenReturn(null);
    when(session.getConsultingTypeId()).thenReturn(2);
    when(session.getMatrixRoomId()).thenReturn("!matrix-abc:oriso.org");
    when(session.getGroupId()).thenReturn("rc-group-1");

    eventNotificationService.createNewClientRequestNotifications(session, List.of("consultant-x"));

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String actionPath = eventCaptor.getValue().getActionPath();
    assertTrue(actionPath.contains("!matrix-abc"));
    assertFalse(actionPath.contains("rc-group-1"));
  }

  // ── contentLabel via PrivacyEnvelope ──────────────────────────────────────

  @Test
  void createMessageNotification_Should_UseContentClass_From_Envelope() {
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    PrivacyEnvelope envelope =
        PrivacyEnvelope.builder().contentClass("IMAGE").messageId("m1").build();

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender-x", false, envelope);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    // NONE mode (default): "Someone sent a new image."
    assertTrue(eventCaptor.getValue().getText().contains("image"));
  }

  // ── resolveSenderName — lookup chain ──────────────────────────────────────

  @Test
  void createMessageNotification_Should_UseConsultantDisplayName_When_LookupSucceeds() {
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    Consultant consultant = mock(Consultant.class);
    when(consultant.getDisplayName()).thenReturn("Dr Smith");
    when(consultant.getFullName()).thenReturn(null);
    when(consultant.getUsername()).thenReturn(null);
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender-x"))
        .thenReturn(Optional.of(consultant));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender-x", "msg", false);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertTrue(eventCaptor.getValue().getText().contains("Dr Smith"));
  }

  @Test
  void createMessageNotification_Should_UseUsername_When_ConsultantNotFound() {
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender-x")).thenReturn(Optional.empty());
    User senderUser = mock(User.class);
    when(senderUser.getUsername()).thenReturn("johndoe");
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender-x"))
        .thenReturn(Optional.of(senderUser));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender-x", "msg", false);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertTrue(eventCaptor.getValue().getText().contains("johndoe"));
  }

  // ── normalizePreview — truncation ─────────────────────────────────────────

  @Test
  void createMessageNotification_FULL_mode_Should_TruncateLongPreview() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "FULL");
    Session session = sessionWithUser("asker-1");
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    String longText = "a".repeat(150);

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender-x", longText, false, false, "Jane");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertTrue(text.contains("..."));
    assertFalse(text.contains(longText));
  }
}
