package de.caritas.cob.userservice.api.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.util.Arrays;
import java.util.List;
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
}
