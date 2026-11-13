package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.CallRoomOrigin;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FE#2209: the verified registration of an encrypted 1:1 session call by the session's own
 * counsellor is the proof share links, knocks and guest seats need. Nobody else may create it.
 */
class MatrixSessionCallBindingServiceTest {
  private static final String SOURCE = "!session:example";
  private static final String MEDIA = "!media:example";
  private static final String COUNSELLOR = "@counsellor:example";
  private static final String ASKER = "@asker:example";
  private static final String OTHER_COUNSELLOR = "@other-counsellor:example";
  private static final long TENANT = 7L;
  private static final long SESSION = 16L;

  private final MatrixCallConversationResolver conversations =
      mock(MatrixCallConversationResolver.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final MatrixCallBindingRepository bindings = mock(MatrixCallBindingRepository.class);
  private final MatrixCallBindingService callBindings = mock(MatrixCallBindingService.class);
  private final RecordingTransactionManager tx = new RecordingTransactionManager();
  private final List<String> matrixCallsInsideTransaction = new ArrayList<>();
  private final MatrixSynapseService matrix =
      mock(
          MatrixSynapseService.class,
          org.mockito.Mockito.withSettings()
              .invocationListeners(
                  report -> {
                    if (tx.isOpen())
                      matrixCallsInsideTransaction.add(report.getInvocation().toString());
                  }));

  private MatrixSessionCallBindingService service;
  private Session session;

  @BeforeEach
  void setUp() {
    service =
        new MatrixSessionCallBindingService(
            conversations, sessions, consultants, users, bindings, callBindings, matrix, tx);
    var conversation = new MatrixCallConversation(SOURCE, TENANT, SESSION, null);
    when(conversations.resolveForWriting(SOURCE)).thenReturn(Optional.of(conversation));
    when(conversations.lockCurrentForWriting(any())).thenReturn(true);
    session = new Session();
    session.setId(SESSION);
    session.setConsultant(consultant("counsellor-id", COUNSELLOR));
    when(sessions.findById(SESSION)).thenReturn(Optional.of(session));
    when(consultants.findByIdAndDeleteDateIsNull("counsellor-id"))
        .thenReturn(Optional.of(consultant("counsellor-id", COUNSELLOR)));
    when(consultants.findByIdAndDeleteDateIsNull("other-counsellor-id"))
        .thenReturn(Optional.of(consultant("other-counsellor-id", OTHER_COUNSELLOR)));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    when(users.findByMatrixUserIdAndDeleteDateIsNull(anyString())).thenReturn(Optional.empty());
    var asker = new User();
    asker.setUserId("asker-id");
    asker.setMatrixUserId(ASKER);
    asker.setTenantId(TENANT);
    when(users.findByMatrixUserIdAndDeleteDateIsNull(ASKER)).thenReturn(Optional.of(asker));
    when(matrix.getCallRoomMembers(SOURCE))
        .thenReturn(Optional.of(List.of(COUNSELLOR, ASKER, OTHER_COUNSELLOR)));
    when(matrix.getCallRoomBinding(anyString(), anyString()))
        .thenReturn(Optional.of(Map.of("call_id", "call", "source_room_id", SOURCE)));
    when(matrix.getCallRoomOrigin(MEDIA, COUNSELLOR))
        .thenReturn(Optional.of(new CallRoomOrigin(COUNSELLOR, COUNSELLOR)));
    when(matrix.getCallRoomOrigin(MEDIA, OTHER_COUNSELLOR))
        .thenReturn(Optional.of(new CallRoomOrigin(OTHER_COUNSELLOR, OTHER_COUNSELLOR)));
    when(bindings.findBySourceRoomIdAndCallId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(bindings.findByMediaRoomId(anyString())).thenReturn(Optional.empty());
    when(callBindings.registerWhileSourceLocked(any())).thenReturn(true);
  }

  @Test
  void theSessionsCounsellorWhoCreatedTheCallRoomGetsTheAuthoritativeBinding() {
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isTrue();

    var captor = ArgumentCaptor.forClass(MatrixCallBinding.class);
    verify(callBindings).registerWhileSourceLocked(captor.capture());
    var binding = captor.getValue();
    assertThat(binding.getSourceRoomId()).isEqualTo(SOURCE);
    assertThat(binding.getCallId()).isEqualTo("call");
    assertThat(binding.getMediaRoomId()).isEqualTo(MEDIA);
    assertThat(binding.getCallerMatrixId()).isEqualTo(COUNSELLOR);
    assertThat(binding.getSessionId()).isEqualTo(SESSION);
    assertThat(binding.getChatId()).isNull();
    assertThat(binding.getTenantId()).isEqualTo(TENANT);
    assertThat(binding.isVideo()).isTrue();
    assertThat(binding.getInviteExpiresAt()).isGreaterThan(binding.getInvitedAt());
    // Invitees are the other people of this session, resolved from live membership.
    assertThat(binding.getInvitedMatrixIds()).isEqualTo(Set.of(ASKER));
    // The lifecycle observes the call room, so the call (and every share link) ends with it.
    verify(matrix).ensureAdminInRoom(MEDIA, COUNSELLOR);
    // #1630: Matrix is never called while the database transaction is open.
    assertThat(matrixCallsInsideTransaction).isEmpty();
  }

  @Test
  void anAdviceSeekerCannotCreateIt() {
    assertThat(service.bind(actor("asker-id", false), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void anotherCounsellorInTheSessionRoomCannotCreateIt() {
    // A supervisor or team counsellor may be a member and may even have created a call room.
    assertThat(service.bind(actor("other-counsellor-id", true), SOURCE, "call", MEDIA, true))
        .isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void aSessionWithoutAnAssignedCounsellorGetsNone() {
    session.setConsultant(null);
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void groupChatsAreNotCovered() {
    when(conversations.resolveForWriting(SOURCE))
        .thenReturn(Optional.of(new MatrixCallConversation(SOURCE, TENANT, null, 45L)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void anotherTraegersCounsellorGetsNone() {
    var actor = actor("counsellor-id", true);
    actor.setTenantId(8L);
    assertThat(service.bind(actor, SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void theCounsellorMustStillBeInTheSessionRoom() {
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(ASKER)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void aCallRoomBoundToAnotherCallOrConversationIsRefused() {
    when(matrix.getCallRoomBinding(MEDIA, COUNSELLOR))
        .thenReturn(Optional.of(Map.of("call_id", "other-call", "source_room_id", SOURCE)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();

    when(matrix.getCallRoomBinding(MEDIA, COUNSELLOR))
        .thenReturn(Optional.of(Map.of("call_id", "call", "source_room_id", "!elsewhere:example")));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void aCallRoomSomebodyElseCreatedIsRefused() {
    when(matrix.getCallRoomOrigin(MEDIA, COUNSELLOR))
        .thenReturn(Optional.of(new CallRoomOrigin(ASKER, COUNSELLOR)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();

    when(matrix.getCallRoomOrigin(MEDIA, COUNSELLOR))
        .thenReturn(Optional.of(new CallRoomOrigin(COUNSELLOR, ASKER)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
  }

  @Test
  void aRenewalOfTheSameCallKeepsTheExistingBinding() {
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(existing(MEDIA, COUNSELLOR)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isTrue();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void aBindingForAnotherCallRoomIsNeverReplaced() {
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(existing("!other-media:example", COUNSELLOR)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void aCallRoomAlreadyBoundElsewhereIsRefused() {
    when(bindings.findByMediaRoomId(MEDIA))
        .thenReturn(
            Optional.of(
                MatrixCallBinding.builder()
                    .sourceRoomId("!elsewhere:example")
                    .callId("call")
                    .mediaRoomId(MEDIA)
                    .callerMatrixId(COUNSELLOR)
                    .build()));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void aSessionThatCannotBeLockedForWritingGetsNone() {
    when(conversations.lockCurrentForWriting(any())).thenReturn(false);
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
    verify(matrix, never()).ensureAdminInRoom(anyString(), anyString());
  }

  private static MatrixCallBinding existing(String media, String caller) {
    return MatrixCallBinding.builder()
        .sourceRoomId(SOURCE)
        .callId("call")
        .mediaRoomId(media)
        .callerMatrixId(caller)
        .sessionId(SESSION)
        .tenantId(TENANT)
        .build();
  }

  private static AuthenticatedUser actor(String id, boolean consultant) {
    var actor = new AuthenticatedUser();
    actor.setUserId(id);
    actor.setTenantId(TENANT);
    actor.setRoles(Set.of(consultant ? "consultant" : "user"));
    return actor;
  }

  private static Consultant consultant(String id, String matrixId) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setMatrixUserId(matrixId);
    consultant.setTenantId(TENANT);
    return consultant;
  }
}
