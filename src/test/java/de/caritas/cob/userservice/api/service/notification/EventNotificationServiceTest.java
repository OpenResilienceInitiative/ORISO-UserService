package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.LocalDateTime;
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

  // ---------------------------------------------------------------------------
  // createNewClientRequestNotifications
  // ---------------------------------------------------------------------------

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

  @Test
  void createNewClientRequestNotifications_swallowsExceptionForOneRecipientAndContinues() {
    Session session = sessionMock();
    when(eventNotificationRepository.save(any()))
        .thenThrow(new RuntimeException("DB error"))
        .thenReturn(mock(EventNotification.class));

    eventNotificationService.createNewClientRequestNotifications(
        session, List.of("consultant-a", "consultant-b"));

    verify(eventNotificationRepository, times(2)).save(any());
  }

  // ---------------------------------------------------------------------------
  // createInquiryAcceptedNotification
  // ---------------------------------------------------------------------------

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
  void createInquiryAcceptedNotification_doesNothingForNullSession() {
    eventNotificationService.createInquiryAcceptedNotification(null, mock(Consultant.class));
    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createInquiryAcceptedNotification_doesNothingWhenUserIsNull() {
    Session session = mock(Session.class);
    when(session.getUser()).thenReturn(null);
    eventNotificationService.createInquiryAcceptedNotification(session, mock(Consultant.class));
    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createInquiryAcceptedNotification_doesNothingWhenUserIdIsNull() {
    Session session = mock(Session.class);
    User user = mock(User.class);
    when(session.getUser()).thenReturn(user);
    when(user.getUserId()).thenReturn(null);
    eventNotificationService.createInquiryAcceptedNotification(session, mock(Consultant.class));
    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createInquiryAcceptedNotification_usesFullNameWhenDisplayNameIsEncoded() throws Exception {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    Consultant consultant = mock(Consultant.class);
    when(consultant.getDisplayName()).thenReturn("enc.someCrypticallyEncodedValue");
    when(consultant.getFullName()).thenReturn("Jane Real Name");

    eventNotificationService.createInquiryAcceptedNotification(session, consultant);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    JsonNode params = objectMapper.readTree(eventCaptor.getValue().getParams());
    assertEquals("Jane Real Name", params.get("consultantName").asText());
  }

  @Test
  void createInquiryAcceptedNotification_returnsCounselorWhenAllConsultantFieldsAreEncoded()
      throws Exception {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    Consultant consultant = mock(Consultant.class);
    when(consultant.getDisplayName()).thenReturn("enc.encodedDisplayName");
    when(consultant.getFullName()).thenReturn("enc.encodedFullName");
    when(consultant.getUsername()).thenReturn("enc.encodedUsername");

    eventNotificationService.createInquiryAcceptedNotification(session, consultant);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    JsonNode params = objectMapper.readTree(eventCaptor.getValue().getParams());
    assertEquals("Counselor", params.get("consultantName").asText());
  }

  // ---------------------------------------------------------------------------
  // createSupervisorAddedNotification
  // ---------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------
  // createSupervisorAssignedNotification
  // ---------------------------------------------------------------------------

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
  void createSupervisorAssignedNotification_doesNothingForNullSession() {
    eventNotificationService.createSupervisorAssignedNotification(null, "consultant-x");
    verify(eventNotificationRepository, never()).save(any());
  }

  // ---------------------------------------------------------------------------
  // createSupervisorRemovedNotification
  // ---------------------------------------------------------------------------

  @Test
  void createSupervisorRemovedNotification_setsSessionAndSupervisorNameParams() throws Exception {
    eventNotificationService.createSupervisorRemovedNotification(
        sessionMock(), "user-1", "Sam Supervisor");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    EventNotification saved = eventCaptor.getValue();
    assertEquals("supervisor.removed", saved.getEventType());
    assertEquals("user-1", saved.getRecipientUserId());
    JsonNode params = objectMapper.readTree(saved.getParams());
    assertEquals(100L, params.get("sessionId").asLong());
    assertEquals("Sam Supervisor", params.get("supervisorName").asText());
  }

  // ---------------------------------------------------------------------------
  // createCounselorRenamedNotification
  // ---------------------------------------------------------------------------

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
  void createCounselorRenamedNotification_doesNothingForNullSession() {
    eventNotificationService.createCounselorRenamedNotification(null, "user-1", "Old", "New");
    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createCounselorRenamedNotification_doesNothingForBlankRecipient() {
    eventNotificationService.createCounselorRenamedNotification(sessionMock(), "  ", "Old", "New");
    verify(eventNotificationRepository, never()).save(any());
  }

  // ---------------------------------------------------------------------------
  // createMessageNotificationFromRoom — routing and guards
  // ---------------------------------------------------------------------------

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
  void createMessageNotificationFromRoom_doesNothingForNullRoomId() {
    eventNotificationService.createMessageNotificationFromRoom(null, "sender", "msg", false);
    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createMessageNotificationFromRoom_doesNothingForBlankRoomId() {
    eventNotificationService.createMessageNotificationFromRoom("  ", "sender", "msg", false);
    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createMessageNotificationFromRoom_doesNothingWhenSessionNotFound() {
    when(sessionRepository.findByGroupId("unknown-room")).thenReturn(Optional.empty());
    eventNotificationService.createMessageNotificationFromRoom(
        "unknown-room", "sender", "msg", false);
    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createMessageNotificationFromRoom_filtersSystemNotificationMessages() {
    Session session = sessionMock();
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", "[SYSTEM_NOTIFICATION] room created", false);

    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createMessageNotificationFromRoom_usesMatrixRoomIdLookupWhenMatrixRoomTrue() {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByMatrixRoomId("!matrix-room-id")).thenReturn(Optional.of(session));

    eventNotificationService.createMessageNotificationFromRoom(
        "!matrix-room-id", "sender", "hello", true);

    verify(sessionRepository).findByMatrixRoomId("!matrix-room-id");
    verify(sessionRepository, never()).findByGroupId(any());
    verify(eventNotificationRepository).save(any());
  }

  @Test
  void createMessageNotificationFromRoom_suppressesNotificationWhenRecipientIsActiveInRoom() {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.updateActiveView("asker-1", "rc-group-1", null, true);
    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", "hello", false);

    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createMessageNotificationFromRoom_doesNotSuppressWhenUserIsInDifferentRoom() {
    eventNotificationService.updateActiveView("asker-1", "different-room", null, true);

    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", "msg", false);

    verify(eventNotificationRepository).save(any());
  }

  @Test
  void createMessageNotificationFromRoom_consultantReceivesNotificationWhenNotSender() {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    Consultant consultant = mock(Consultant.class);
    when(consultant.getId()).thenReturn("consultant-1");
    when(session.getConsultant()).thenReturn(consultant);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    // sender is the user — consultant should receive notification, not user
    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "asker-1", "hello", false);

    verify(eventNotificationRepository, times(1)).save(eventCaptor.capture());
    assertEquals("consultant-1", eventCaptor.getValue().getRecipientUserId());
  }

  // ---------------------------------------------------------------------------
  // createThreadReplyNotificationFromRoom
  // ---------------------------------------------------------------------------

  @Test
  void createThreadReplyNotificationFromRoom_doesNothingForBlankRoomId() {
    eventNotificationService.createThreadReplyNotificationFromRoom(
        "  ", "sender", "reply", "thread-1", false);
    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createThreadReplyNotificationFromRoom_doesNothingWhenSessionNotFound() {
    when(sessionRepository.findByGroupId("missing-room")).thenReturn(Optional.empty());
    eventNotificationService.createThreadReplyNotificationFromRoom(
        "missing-room", "sender", "reply", "thread-1", false);
    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createThreadReplyNotificationFromRoom_suppressesWhenUserActiveInSameThread() {
    eventNotificationService.updateActiveView("asker-1", "rc-group-1", "thread-root-1", true);

    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createThreadReplyNotificationFromRoom(
        "rc-group-1", "sender", "reply", "thread-root-1", false);

    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createThreadReplyNotificationFromRoom_doesNotSuppressWhenThreadRootIdMismatch() {
    eventNotificationService.updateActiveView("asker-1", "rc-group-1", "thread-root-1", true);

    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createThreadReplyNotificationFromRoom(
        "rc-group-1", "sender", "reply", "different-thread", false);

    verify(eventNotificationRepository).save(any());
  }

  // ---------------------------------------------------------------------------
  // getFeed — page and perPage clamping
  // ---------------------------------------------------------------------------

  @Test
  void getFeed_clampsNegativePageToZeroAndOverLimitPerPageToHundred() {
    when(eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDesc(any(), any()))
        .thenReturn(List.of());
    when(eventNotificationRepository.countByRecipientUserIdAndReadDateIsNull(any())).thenReturn(0L);

    var result = eventNotificationService.getFeed("user-1", -5, 999);

    assertThat(result.getPage()).isEqualTo(0);
    assertThat(result.getPerPage()).isEqualTo(100);
  }

  @Test
  void getFeed_clampsZeroPerPageToOne() {
    when(eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDesc(any(), any()))
        .thenReturn(List.of());
    when(eventNotificationRepository.countByRecipientUserIdAndReadDateIsNull(any())).thenReturn(0L);

    var result = eventNotificationService.getFeed("user-1", 0, 0);

    assertThat(result.getPerPage()).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // markAsRead
  // ---------------------------------------------------------------------------

  @Test
  void markAsRead_doesNotSaveWhenNotificationAlreadyRead() {
    EventNotification alreadyRead = mock(EventNotification.class);
    when(alreadyRead.getReadDate()).thenReturn(LocalDateTime.now());
    when(eventNotificationRepository.findByIdAndRecipientUserId(42L, "user-1"))
        .thenReturn(Optional.of(alreadyRead));

    eventNotificationService.markAsRead("user-1", 42L);

    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void markAsRead_savesWhenNotificationIsUnread() {
    EventNotification unread = mock(EventNotification.class);
    when(unread.getReadDate()).thenReturn(null);
    when(eventNotificationRepository.findByIdAndRecipientUserId(42L, "user-1"))
        .thenReturn(Optional.of(unread));

    eventNotificationService.markAsRead("user-1", 42L);

    verify(eventNotificationRepository).save(unread);
  }

  // ---------------------------------------------------------------------------
  // markAllAsRead
  // ---------------------------------------------------------------------------

  @Test
  void markAllAsRead_doesNothingWhenNoUnreadNotifications() {
    when(eventNotificationRepository.findByRecipientUserIdAndReadDateIsNull("user-1"))
        .thenReturn(List.of());

    eventNotificationService.markAllAsRead("user-1");

    verify(eventNotificationRepository, never()).saveAll(any());
  }

  @Test
  void markAllAsRead_setsReadDateOnAllUnreadAndSaves() {
    EventNotification n1 = mock(EventNotification.class);
    EventNotification n2 = mock(EventNotification.class);
    when(eventNotificationRepository.findByRecipientUserIdAndReadDateIsNull("user-1"))
        .thenReturn(List.of(n1, n2));

    eventNotificationService.markAllAsRead("user-1");

    verify(n1).setReadDate(any());
    verify(n2).setReadDate(any());
    verify(eventNotificationRepository).saveAll(any());
  }

  // ---------------------------------------------------------------------------
  // clearFeed
  // ---------------------------------------------------------------------------

  @Test
  void clearFeed_delegatesToDeleteByRecipientUserId() {
    eventNotificationService.clearFeed("user-1");
    verify(eventNotificationRepository).deleteByRecipientUserId("user-1");
  }

  // ---------------------------------------------------------------------------
  // updateActiveView
  // ---------------------------------------------------------------------------

  @Test
  void updateActiveView_doesNothingForBlankUserId() {
    // No exception and no state change that would suppress future notifications
    eventNotificationService.updateActiveView("  ", "room-1", null, true);

    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", "msg", false);

    // Notification must still be delivered — blank userId never registered any suppression
    verify(eventNotificationRepository).save(any());
  }

  @Test
  void updateActiveView_removesViewWhenActiveFalse() {
    eventNotificationService.updateActiveView("asker-1", "rc-group-1", null, true);
    eventNotificationService.updateActiveView("asker-1", "rc-group-1", null, false);

    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", "msg", false);

    verify(eventNotificationRepository).save(any());
  }

  @Test
  void updateActiveView_removesViewWhenRoomIdIsBlankEvenIfActiveTrue() {
    eventNotificationService.updateActiveView("asker-1", "rc-group-1", null, true);
    eventNotificationService.updateActiveView("asker-1", "  ", null, true);

    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", "msg", false);

    verify(eventNotificationRepository).save(any());
  }

  // ---------------------------------------------------------------------------
  // createEvent — low-level builder guards
  // ---------------------------------------------------------------------------

  @Test
  void createEvent_doesNothingForBlankRecipientUserId() {
    eventNotificationService.createEvent(
        "  ", "message.new", EventNotificationService.CATEGORY_MESSAGE, "T", "B", null, 1L, 1L);
    verify(eventNotificationRepository, never()).save(any());
  }

  @Test
  void createEvent_defaultsToCategorySystemWhenNullCategoryPassed() {
    eventNotificationService.createEvent(
        "user-1", "message.new", null, "Title", "Text", null, 1L, 1L);
    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertEquals(EventNotificationService.CATEGORY_SYSTEM, eventCaptor.getValue().getCategory());
  }

  // ---------------------------------------------------------------------------
  // Notification preview modes (via createMessageNotificationFromRoom)
  // ---------------------------------------------------------------------------

  @Test
  void createMessageNotificationFromRoom_nonePreviewModeDoesNotIncludeMessageBody() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "NONE");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", "secret body", false);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertThat(text).contains("sent a new message");
    assertThat(text).doesNotContain("secret body");
    assertThat(text).doesNotContain("[content hidden]");
  }

  @Test
  void createMessageNotificationFromRoom_maskedPreviewModeShowsRedactedPlaceholder() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "MASKED");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", "secret body", false);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertThat(text).contains("[content hidden]");
    assertThat(text).doesNotContain("secret body");
  }

  @Test
  void createMessageNotificationFromRoom_fullPreviewModeIncludesMessageBody() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "FULL");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", "hello there", false);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getText()).contains("hello there");
  }

  @Test
  void createMessageNotificationFromRoom_invalidPreviewModeDefaultsToNone() {
    ReflectionTestUtils.setField(
        eventNotificationService, "notificationPreviewMode", "INVALID_VALUE");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", "hello", false);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    // IllegalArgumentException on valueOf → falls back to NONE, which omits the preview
    assertThat(eventCaptor.getValue().getText()).doesNotContain("hello");
    assertThat(eventCaptor.getValue().getText()).doesNotContain("[content hidden]");
  }

  // ---------------------------------------------------------------------------
  // resolveSenderName paths (exercised via createMessageNotificationFromRoom)
  // ---------------------------------------------------------------------------

  @Test
  void resolveSenderName_usesDisplayNameWhenNotEncoded() {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender-id", "msg", false, false, "Alice Consultant");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getParams()).contains("Alice Consultant");
  }

  @Test
  void resolveSenderName_fallsBackToConsultantRepositoryWhenNoDisplayName() {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    Consultant consultant = mock(Consultant.class);
    when(consultant.getDisplayName()).thenReturn("Bob Smith");
    when(consultantRepository.findByIdAndDeleteDateIsNull("consultant-sender"))
        .thenReturn(Optional.of(consultant));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "consultant-sender", "msg", false);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getParams()).contains("Bob Smith");
  }

  @Test
  void resolveSenderName_fallsBackToUserRepositoryWhenNotConsultant() {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    User senderUser = mock(User.class);
    when(senderUser.getUsername()).thenReturn("carol-user");
    when(consultantRepository.findByIdAndDeleteDateIsNull("user-sender"))
        .thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("user-sender"))
        .thenReturn(Optional.of(senderUser));

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "user-sender", "msg", false);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getParams()).contains("carol-user");
  }

  @Test
  void resolveSenderName_returnsSomeoneWhenAllRepoLookupsFail() {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("unknown")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("unknown")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("unknown")).thenReturn(Optional.empty());

    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "unknown", "msg", false);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getParams()).contains("Someone");
  }

  // ---------------------------------------------------------------------------
  // getFeed — toItem mapping (hit previously uncovered toItem path)
  // ---------------------------------------------------------------------------

  @Test
  void getFeed_mapsNotificationsToItemsWithReadAtAndCreatedAt() {
    EventNotification n = new EventNotification();
    n.setId(99L);
    n.setEventType("message.new");
    n.setCategory(EventNotificationService.CATEGORY_MESSAGE);
    n.setTitle("New message");
    n.setText("hello");
    n.setParams("{\"sessionId\":1}");
    n.setActionPath("/sessions/user/view/room/1");
    n.setSourceSessionId(1L);
    n.setReadDate(LocalDateTime.of(2026, 1, 1, 12, 0));
    n.setCreateDate(LocalDateTime.of(2026, 1, 1, 11, 0));

    when(eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDesc(any(), any()))
        .thenReturn(List.of(n));
    when(eventNotificationRepository.countByRecipientUserIdAndReadDateIsNull(any())).thenReturn(0L);

    var result = eventNotificationService.getFeed("user-1", 0, 10);

    assertThat(result.getItems()).hasSize(1);
    var item = result.getItems().get(0);
    assertThat(item.getId()).isEqualTo(99L);
    assertThat(item.getEventType()).isEqualTo("message.new");
    assertThat(item.getReadAt()).isNotNull();
    assertThat(item.getCreatedAt()).isNotNull();
  }

  @Test
  void getFeed_setsReadAtNullWhenNotificationIsUnread() {
    EventNotification n = new EventNotification();
    n.setId(1L);
    n.setEventType("request.new");
    n.setCategory(EventNotificationService.CATEGORY_SYSTEM);
    n.setTitle("T");
    n.setText("B");
    n.setCreateDate(LocalDateTime.now());
    n.setReadDate(null);

    when(eventNotificationRepository.findByRecipientUserIdOrderByCreateDateDesc(any(), any()))
        .thenReturn(List.of(n));
    when(eventNotificationRepository.countByRecipientUserIdAndReadDateIsNull(any())).thenReturn(1L);

    var result = eventNotificationService.getFeed("user-1", 0, 10);

    assertThat(result.getItems().get(0).getReadAt()).isNull();
    assertThat(result.getUnreadCount()).isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // buildThreadReplyNotificationText — MASKED + FULL modes
  // ---------------------------------------------------------------------------

  @Test
  void createThreadReplyNotificationFromRoom_maskedModeShowsRedactedPlaceholder() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "MASKED");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    eventNotificationService.createThreadReplyNotificationFromRoom(
        "rc-group-1", "sender", "secret reply", "thread-1", false);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertThat(text).contains("replied in a thread");
    assertThat(text).contains("[content hidden]");
    assertThat(text).doesNotContain("secret reply");
  }

  @Test
  void createThreadReplyNotificationFromRoom_fullModeIncludesPreviewAndParentPreview() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "FULL");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    eventNotificationService.createThreadReplyNotificationFromRoom(
        "rc-group-1", "sender", "my reply text", "thread-1", false, false, null, "parent message");

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertThat(text).contains("my reply text");
    assertThat(text).contains("parent message");
  }

  @Test
  void createThreadReplyNotificationFromRoom_fullModeEmptyPreviewFallsBackToMessageId() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "FULL");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    eventNotificationService.createThreadReplyNotificationFromRoom(
        "rc-group-1", "sender", null, "thread-1", false, false, null, null);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertThat(text).contains("replied in a thread");
    assertThat(text).contains("n/a");
  }

  // ---------------------------------------------------------------------------
  // Untested delegator overloads — PrivacyEnvelope variants
  // ---------------------------------------------------------------------------

  @Test
  void createMessageNotificationFromRoom_envelopeOverloadDelegatesAndCreatesNotification() {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    PrivacyEnvelope envelope =
        PrivacyEnvelope.builder().messageId("msg-1").contentClass("IMAGE").build();
    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", false, envelope);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getEventType()).isEqualTo("message.new");
  }

  @Test
  void createThreadReplyNotificationFromRoom_envelopeOverloadDelegatesAndCreatesNotification() {
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    PrivacyEnvelope envelope =
        PrivacyEnvelope.builder().messageId("msg-2").contentClass("FILE").build();
    eventNotificationService.createThreadReplyNotificationFromRoom(
        "rc-group-1", "sender", "thread-root-1", false, envelope);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getEventType()).isEqualTo("thread.reply.new");
  }

  // ---------------------------------------------------------------------------
  // contentLabel — via NONE preview mode (shows contentLabel in text)
  // ---------------------------------------------------------------------------

  @Test
  void createMessageNotificationFromRoom_noneMode_usesContentClassAsLabel() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "NONE");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    PrivacyEnvelope imageEnvelope =
        PrivacyEnvelope.builder().messageId("m1").contentClass("IMAGE").build();
    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", null, false, false, null, imageEnvelope);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getText()).contains("image");
  }

  @Test
  void createMessageNotificationFromRoom_noneMode_fileContentClassLabelIsFile() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "NONE");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    PrivacyEnvelope fileEnvelope =
        PrivacyEnvelope.builder().messageId("m2").contentClass("FILE").build();
    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", null, false, false, null, fileEnvelope);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getText()).contains("file");
  }

  @Test
  void createMessageNotificationFromRoom_noneMode_audioContentClassLabelIsAudioMessage() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "NONE");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    PrivacyEnvelope audioEnvelope =
        PrivacyEnvelope.builder().messageId("m3").contentClass("AUDIO").build();
    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", null, false, false, null, audioEnvelope);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getText()).contains("audio message");
  }

  @Test
  void createMessageNotificationFromRoom_noneMode_videoContentClassLabelIsVideoMessage() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "NONE");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    PrivacyEnvelope videoEnvelope =
        PrivacyEnvelope.builder().messageId("m4").contentClass("VIDEO").build();
    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", null, false, false, null, videoEnvelope);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getText()).contains("video message");
  }

  // ---------------------------------------------------------------------------
  // normalizePreview — long message truncation
  // ---------------------------------------------------------------------------

  @Test
  void createMessageNotificationFromRoom_fullMode_truncatesLongPreviewAt117CharsWithEllipsis() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "FULL");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    String longMessage = "A".repeat(150);
    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", longMessage, false);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertThat(text).contains("...");
    assertThat(text).doesNotContain("A".repeat(118));
  }

  // ---------------------------------------------------------------------------
  // buildMessageNotificationText FULL with empty preview (messageId fallback)
  // ---------------------------------------------------------------------------

  @Test
  void createMessageNotificationFromRoom_fullMode_emptyPreviewFallsBackToMessageId() {
    ReflectionTestUtils.setField(eventNotificationService, "notificationPreviewMode", "FULL");
    Session session = sessionMock();
    User user = mock(User.class);
    when(user.getUserId()).thenReturn("asker-1");
    when(session.getUser()).thenReturn(user);
    when(sessionRepository.findByGroupId("rc-group-1")).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(userRepository.findByUserIdAndDeleteDateIsNull("sender")).thenReturn(Optional.empty());
    when(identityTombstoneService.resolveDisplayLabel("sender")).thenReturn(Optional.empty());

    PrivacyEnvelope envelope = PrivacyEnvelope.builder().messageId("evt-123").build();
    eventNotificationService.createMessageNotificationFromRoom(
        "rc-group-1", "sender", null, false, false, null, envelope);

    verify(eventNotificationRepository).save(eventCaptor.capture());
    String text = eventCaptor.getValue().getText();
    assertThat(text).contains("evt-123");
  }
}
