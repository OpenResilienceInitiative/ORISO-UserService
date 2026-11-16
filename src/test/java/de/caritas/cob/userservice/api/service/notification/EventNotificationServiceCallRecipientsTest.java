package de.caritas.cob.userservice.api.service.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
 * that session reaches a counsellor only while they may write in the case (assigned or permitted
 * team writer); the advice seeker and group chat members are not gated. Regression: every silent
 * member got "Invitation to an internal group call" for a case they cannot open.
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
  @Mock private MatrixCaseReplyActorAuthorizer caseWriterAuthorizer;

  @Mock
  private org.springframework.beans.factory.ObjectProvider<MatrixCaseReplyActorAuthorizer>
      caseWriterAuthorizerProvider;

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
    lenient().when(session.getId()).thenReturn(SESSION_ID);
    when(caseWriterAuthorizerProvider.getIfAvailable()).thenReturn(caseWriterAuthorizer);
    when(counsellor.getTenantId()).thenReturn(TENANT_ID);
    var silent = mock(Consultant.class);
    when(silent.getTenantId()).thenReturn(TENANT_ID);
    when(consultantRepository.findById(COUNSELLOR)).thenReturn(Optional.of(counsellor));
    when(consultantRepository.findById(SILENT_COLLEAGUE)).thenReturn(Optional.of(silent));
    when(caseWriterAuthorizer.isCurrentAssignedOrTeamWriter(session, counsellor)).thenReturn(true);
    when(caseWriterAuthorizer.isCurrentAssignedOrTeamWriter(session, silent)).thenReturn(false);
    // The call resolver names the session by its Matrix room; so does the gate.
    lenient().when(sessionRepository.findByMatrixRoomId(ROOM)).thenReturn(Optional.of(session));
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
  void anUnknownOrForeignTenantSessionNotifiesNoCounsellor() {
    notifyAll(new MatrixCallConversation(ROOM, 99L, SESSION_ID, null), COUNSELLOR);
    notifyAll(new MatrixCallConversation(ROOM, TENANT_ID, 70L, null), COUNSELLOR);

    verify(deduplicationWriter, never()).persistInNewTransaction(any(EventNotification.class));
  }

  @Test
  void aDeletedOrForeignTenantCounsellorGetsNothingEvenIfTheCaseRuleWouldAllowIt() {
    var deleted = mock(Consultant.class);
    when(deleted.getTenantId()).thenReturn(TENANT_ID);
    when(deleted.getDeleteDate()).thenReturn(java.time.LocalDateTime.now());
    var foreign = mock(Consultant.class);
    when(foreign.getTenantId()).thenReturn(99L);
    when(consultantRepository.findById("deleted-1")).thenReturn(Optional.of(deleted));
    when(consultantRepository.findById("foreign-1")).thenReturn(Optional.of(foreign));
    when(caseWriterAuthorizer.isCurrentAssignedOrTeamWriter(any(), any())).thenReturn(true);

    notifyAll(sessionCall, "deleted-1");
    notifyAll(sessionCall, "foreign-1");

    verify(deduplicationWriter, never()).persistInNewTransaction(any(EventNotification.class));
  }

  @Test
  void aGroupChatCallStillReachesEveryMember() {
    notifyAll(new MatrixCallConversation(ROOM, TENANT_ID, null, 8L), SILENT_COLLEAGUE);

    verify(deduplicationWriter, times(4)).persistInNewTransaction(any(EventNotification.class));
  }
}
