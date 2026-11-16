package de.caritas.cob.userservice.api.service.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallConversation;
import de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * A 1:1 session room also holds the department's silent counsellors (ADR-002 §1). Call activity of
 * that session reaches only its advice seeker and its assigned counsellor; a group chat's call
 * still reaches every member. Regression: every silent member got "Invitation to an internal group
 * call" for a case they cannot open.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventNotificationServiceCallRecipientsTest {

  private static final String ROOM = "!session-room:matrix.example";
  private static final long SESSION_ID = 69L;
  private static final long TENANT_ID = 7L;
  private static final String ASKER = "asker-1";
  private static final String COUNSELLOR = "counsellor-1";
  private static final String SILENT_COLLEAGUE = "colleague-1";

  @InjectMocks private EventNotificationService service;
  @Mock private EventNotificationRepository eventNotificationRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserRepository userRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private IdentityTombstoneService identityTombstoneService;
  @Mock private EventNotificationDeduplicationWriter deduplicationWriter;
  @Mock private MatrixFeedUpdateSignalService feedUpdateSignalService;

  @Spy
  private ConsultantDisplayNameResolver consultantDisplayNameResolver =
      new ConsultantDisplayNameResolver();

  private final MatrixCallConversation sessionCall =
      new MatrixCallConversation(ROOM, TENANT_ID, SESSION_ID, null);

  @BeforeEach
  void setUp() {
    var session = mock(Session.class);
    var asker = mock(User.class);
    var counsellor = mock(Consultant.class);
    when(asker.getUserId()).thenReturn(ASKER);
    when(counsellor.getId()).thenReturn(COUNSELLOR);
    when(session.getUser()).thenReturn(asker);
    when(session.getConsultant()).thenReturn(counsellor);
    when(session.getTenantId()).thenReturn(TENANT_ID);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
  }

  private void notifyAll(MatrixCallConversation conversation, String recipient) {
    service.createCallInvitationNotification(
        conversation, recipient, true, COUNSELLOR, "call-1", "!media:x", true);
    service.createCallStartedNotification(
        conversation, recipient, true, "call-1", "!media:x", true);
    service.createCallEndedNotification(conversation, recipient, true, "call-1", "!media:x", 0, 1);
    service.createCallMissedNotification(conversation, recipient, true, "call-1", "!media:x", true);
  }

  @Test
  void aSilentDepartmentColleagueGetsNoCallActivityOfASession() {
    notifyAll(sessionCall, SILENT_COLLEAGUE);

    verify(deduplicationWriter, never()).persistInNewTransaction(any(EventNotification.class));
  }

  @Test
  void theAdviceSeekerAndTheAssignedCounsellorStillGetIt() {
    notifyAll(sessionCall, ASKER);
    notifyAll(sessionCall, COUNSELLOR);

    verify(deduplicationWriter, times(8)).persistInNewTransaction(any(EventNotification.class));
  }

  @Test
  void anUnknownOrForeignTenantSessionNotifiesNobody() {
    notifyAll(new MatrixCallConversation(ROOM, 99L, SESSION_ID, null), ASKER);
    notifyAll(new MatrixCallConversation(ROOM, TENANT_ID, 70L, null), ASKER);

    verify(deduplicationWriter, never()).persistInNewTransaction(any(EventNotification.class));
  }

  @Test
  void aGroupChatCallStillReachesEveryMember() {
    notifyAll(new MatrixCallConversation(ROOM, TENANT_ID, null, 8L), SILENT_COLLEAGUE);

    verify(deduplicationWriter, times(4)).persistInNewTransaction(any(EventNotification.class));
  }
}
