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
}
