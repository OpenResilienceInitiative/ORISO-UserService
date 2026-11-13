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
  private final MatrixCallLifecycleService lifecycle = mock(MatrixCallLifecycleService.class);
  private final OrisoRoomRegistry rooms = mock(OrisoRoomRegistry.class);
  private final SessionCallAuthority authority = mock(SessionCallAuthority.class);
  private final de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository
      observations =
          mock(de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository.class);
  private final de.caritas.cob.userservice.api.port.out.MatrixCallObservationEndRepository ends =
      mock(de.caritas.cob.userservice.api.port.out.MatrixCallObservationEndRepository.class);
  private final MatrixCallGuestService callGuests = mock(MatrixCallGuestService.class);
  private final de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository links =
      mock(de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository.class);
  private final de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository knockRows =
      mock(de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository.class);
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
            conversations,
            sessions,
            consultants,
            users,
            bindings,
            callBindings,
            matrix,
            lifecycle,
            rooms,
            authority,
            observations,
            ends,
            callGuests,
            links,
            knockRows,
            tx);
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
    when(lifecycle.isActiveRecipient(anyString())).thenReturn(true);
    when(callGuests.revokeBeforeTakeOver(any())).thenReturn(true);
    when(callGuests.noSeatOutstanding(any())).thenReturn(true);
    // Only the session's assigned counsellor's binding carries authority (SessionCallAuthority).
    when(authority.grantsGuestAuthority(any()))
        .thenAnswer(
            call ->
                COUNSELLOR.equals(((MatrixCallBinding) call.getArgument(0)).getCallerMatrixId()));
  }

  @Test
  void aSuspendedCounsellorCannotCreateIt() {
    when(lifecycle.isActiveRecipient("counsellor-id")).thenReturn(false);
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
    // Refused before any Matrix lookup or database write.
    verifyNoInteractions(matrix);
  }

  @Test
  void aDeletedCounsellorCannotCreateIt() {
    when(consultants.findByIdAndDeleteDateIsNull("counsellor-id")).thenReturn(Optional.empty());
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
    verifyNoInteractions(matrix);
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
    verifyNoInteractions(matrix);
  }

  @Test
  void anotherCounsellorInTheSessionRoomCannotCreateIt() {
    // A supervisor or team counsellor may be a member and may even have created a call room.
    assertThat(service.bind(actor("other-counsellor-id", true), SOURCE, "call", MEDIA, true))
        .isFalse();
    verifyNoInteractions(callBindings);
    verifyNoInteractions(matrix);
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
    return existing(null, media, caller);
  }

  private static MatrixCallBinding existing(Long id, String media, String caller) {
    return MatrixCallBinding.builder()
        .id(id)
        .sourceRoomId(SOURCE)
        .callId("call")
        .mediaRoomId(media)
        .callerMatrixId(caller)
        .sessionId(SESSION)
        .tenantId(TENANT)
        .invitedAt(System.currentTimeMillis())
        .inviteExpiresAt(System.currentTimeMillis() + 60_000)
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

  // --- Review round 1 (13 Nov) ---

  @Test
  void aKnownOrisoConversationRoomIsNeverACallRoom() {
    // A counsellor who created two session rooms must not bind S1's call to S2's room.
    when(rooms.isKnownConversationRoom(MEDIA)).thenReturn(true);
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verifyNoInteractions(callBindings);
    verifyNoInteractions(matrix);
  }

  private static de.caritas.cob.userservice.api.model.MatrixCallObservationEnd end(String actor) {
    return de.caritas.cob.userservice.api.model.MatrixCallObservationEnd.builder()
        .sourceRoomId(SOURCE)
        .callId("call")
        .actorMatrixId(actor)
        .tenantId(TENANT)
        .sessionId(SESSION)
        .endedAt(System.currentTimeMillis())
        .build();
  }

  @Test
  void aCallTheCounsellorAlreadyEndedIsNotBoundByALateRegistration() {
    // #1641 "ending wins": the counsellor's own stored end, even with a live registration.
    when(ends.findForUpdateBySourceRoomIdAndCallIdAndActorMatrixId(SOURCE, "call", COUNSELLOR))
        .thenReturn(Optional.of(end(COUNSELLOR)));
    when(observations.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(observation(System.currentTimeMillis() + 60_000)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void aNonCallersEndOrRegistrationNeverBlocksTheBinding() {
    // Round 3 (R1): only the counsellor's own end or own registration counts as "ended"; an
    // asker's end, or an asker's ended or lapsed registration of the same call id, does not.
    when(ends.findForUpdateBySourceRoomIdAndCallIdAndActorMatrixId(SOURCE, "call", ASKER))
        .thenReturn(Optional.of(end(ASKER)));
    var askers = observation(System.currentTimeMillis() - 1);
    askers.finish(System.currentTimeMillis() - 1);
    when(observations.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(askerObservation(askers)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isTrue();
    verify(callBindings).registerWhileSourceLocked(any());
  }

  private static de.caritas.cob.userservice.api.model.MatrixCallObservation askerObservation(
      de.caritas.cob.userservice.api.model.MatrixCallObservation template) {
    return de.caritas.cob.userservice.api.model.MatrixCallObservation.builder()
        .origin(template.getOrigin())
        .sourceRoomId(SOURCE)
        .callId("call")
        .mediaRoomId("!asker-media:example")
        .creatorMatrixId(ASKER)
        .tenantId(TENANT)
        .sessionId(SESSION)
        .registeredAt(template.getRegisteredAt())
        .lastSeenAt(template.getLastSeenAt())
        .expiresAt(template.getExpiresAt())
        .endedAt(template.getEndedAt())
        .build();
  }

  @Test
  void somebodyElsesEndDoesNotBlockTheBindingOfARegisteredLiveCall() {
    when(ends.findForUpdateBySourceRoomIdAndCallIdAndActorMatrixId(SOURCE, "call", ASKER))
        .thenReturn(Optional.of(end(ASKER)));
    when(observations.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(observation(System.currentTimeMillis() + 60_000)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isTrue();
  }

  @Test
  void anEndedOrExpiredObservationOfTheCallRefusesTheBinding() {
    long now = System.currentTimeMillis();
    var ended = observation(now + 60_000);
    ended.finish(now);
    when(observations.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(ended));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();

    when(observations.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(observation(now - 1)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void aLiveObservationOfTheCallDoesNotBlockTheBinding() {
    when(observations.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(observation(System.currentTimeMillis() + 60_000)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isTrue();
    verify(callBindings).registerWhileSourceLocked(any());
  }

  @Test
  void theAssignmentIsCheckedAgainUnderTheLock() {
    // The session was reassigned between the check and the write.
    var reassigned = new Session();
    reassigned.setId(SESSION);
    reassigned.setConsultant(consultant("other-counsellor-id", OTHER_COUNSELLOR));
    when(sessions.findById(SESSION)).thenReturn(Optional.of(session), Optional.of(reassigned));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void aSuspensionDuringTheMatrixChecksIsSeenUnderTheLock() {
    when(lifecycle.isActiveRecipient("counsellor-id")).thenReturn(true, false);
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void anEndedBindingIsNotRenewed() {
    var ended = existing(MEDIA, COUNSELLOR);
    ended.finish(System.currentTimeMillis());
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(ended));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void aLiveExactBindingIsConfirmedWithoutAnyMatrixLookup() {
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(existing(MEDIA, COUNSELLOR)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isTrue();
    verifyNoInteractions(matrix);
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void anAuthorityLessBindingIsTakenOverInPlaceByTheAssignedCounsellor() {
    // FE#2209 decision: an asker's (or unassigned colleague's) invite never blocks bind().
    var askers = existing(42L, "!asker-media:example", ASKER);
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(askers));
    when(bindings.lockById(42L)).thenReturn(Optional.of(askers));

    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isTrue();

    verify(bindings).saveAndFlush(askers);
    assertThat(askers.getId()).isEqualTo(42L);
    assertThat(askers.getCallerMatrixId()).isEqualTo(COUNSELLOR);
    assertThat(askers.getMediaRoomId()).isEqualTo(MEDIA);
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  @Test
  void anEndedOrLapsedAuthorityLessBindingIsStillTakenOver() {
    // Round 3 (R1): an asker's binding that ended or lapsed (lifetime=1) never blocks the assigned
    // counsellor; the takeover starts the counsellor's call afresh on the same row.
    var ended = existing(42L, "!asker-media:example", ASKER);
    ended.finish(System.currentTimeMillis());
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(ended));
    when(bindings.lockById(42L)).thenReturn(Optional.of(ended));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isTrue();
    assertThat(ended.getEndedAt()).isNull();
    assertThat(ended.getCallerMatrixId()).isEqualTo(COUNSELLOR);

    var lapsed =
        MatrixCallBinding.builder()
            .id(43L)
            .sourceRoomId(SOURCE)
            .callId("call")
            .mediaRoomId("!asker-media:example")
            .callerMatrixId(ASKER)
            .sessionId(SESSION)
            .tenantId(TENANT)
            .invitedAt(System.currentTimeMillis() - 120_000)
            .inviteExpiresAt(System.currentTimeMillis() - 60_000)
            .build();
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(lapsed));
    when(bindings.lockById(43L)).thenReturn(Optional.of(lapsed));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isTrue();
    assertThat(lapsed.getCallerMatrixId()).isEqualTo(COUNSELLOR);
  }

  @Test
  void aBindingThatAppearsAfterTheLookupIsLockedBeforeTheSessionOnTheRetry() {
    // Round 3 lock order (binding, then session): a binding inserted between bind's lookup and
    // its transaction was not locked first, so that attempt is rolled back and retried.
    var askers = existing(42L, "!asker-media:example", ASKER);
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.empty(), Optional.of(askers));
    when(bindings.lockById(42L)).thenReturn(Optional.of(askers));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isTrue();
    var order = org.mockito.Mockito.inOrder(bindings, conversations);
    order.verify(bindings).lockById(42L);
    order.verify(conversations).lockCurrentForWriting(any());
    verify(callBindings, never()).registerWhileSourceLocked(any());
    assertThat(askers.getCallerMatrixId()).isEqualTo(COUNSELLOR);
  }

  @Test
  void aLapsedRegistrationLeaseDoesNotBlockATakeOver_OnlyAnExplicitEndDoes() {
    // CodeRabbit 4562143186: a lease is not renewed once a binding exists, so a lapsed lease says
    // nothing about the call; the takeover counts explicit ends only.
    var askers = existing(42L, "!asker-media:example", ASKER);
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(askers));
    when(bindings.lockById(42L)).thenReturn(Optional.of(askers));
    when(observations.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(observation(System.currentTimeMillis() - 1)));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isTrue();

    var ended = observation(System.currentTimeMillis() + 60_000);
    ended.finish(System.currentTimeMillis());
    var again = existing(43L, "!asker-media-2:example", ASKER);
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(again));
    when(bindings.lockById(43L)).thenReturn(Optional.of(again));
    when(observations.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(ended));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
  }

  @Test
  void aTakeOverIsRefusedWhenAnotherBindingOwnsTheCounsellorsCallRoom() {
    var askers = existing(42L, "!asker-media:example", ASKER);
    var owner = existing(7L, MEDIA, COUNSELLOR);
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(askers));
    when(bindings.findByMediaRoomId(MEDIA)).thenReturn(Optional.of(owner));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(bindings, never()).saveAndFlush(any());
  }

  @Test
  void anExistingBindingForTheSameCallRoomButAnotherSessionIsRefused() {
    var otherSession =
        MatrixCallBinding.builder()
            .sourceRoomId(SOURCE)
            .callId("call")
            .mediaRoomId(MEDIA)
            .callerMatrixId(COUNSELLOR)
            .sessionId(SESSION + 1)
            .tenantId(TENANT)
            .invitedAt(System.currentTimeMillis())
            .inviteExpiresAt(System.currentTimeMillis() + 60_000)
            .build();
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(otherSession));
    assertThat(service.bind(actor("counsellor-id", true), SOURCE, "call", MEDIA, true)).isFalse();
    verify(callBindings, never()).registerWhileSourceLocked(any());
  }

  // --- Review round 2 (13 Nov): who ends the binding, inside the end transaction ---

  private final MatrixCallConversation sessionConversation =
      new MatrixCallConversation(SOURCE, TENANT, SESSION, null);

  private MatrixCallBinding liveBinding() {
    var live = existing(5L, MEDIA, COUNSELLOR);
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(live));
    when(bindings.lockById(5L)).thenReturn(Optional.of(live));
    return live;
  }

  private static void inCall(MatrixCallBinding binding, String matrixId, long expiresAt) {
    binding
        .getDevices()
        .put(
            "_" + matrixId,
            new de.caritas.cob.userservice.api.model.MatrixCallDevice(
                matrixId, 1L, expiresAt, true, "$" + matrixId));
  }

  @Test
  void theCallerEndsTheBinding() {
    var live = liveBinding();
    long now = System.currentTimeMillis();
    assertThat(
            service.endWhileSessionLocked(
                actor("counsellor-id", true), sessionConversation, "call", now, 5L))
        .isEqualTo(MEDIA);
    assertThat(live.getEndedAt()).isNotNull();
    verify(bindings).save(live);
    verifyNoInteractions(matrix);
  }

  @Test
  void anActiveColleagueOfTheTraegerWhoIsInTheCallEndsIt() {
    var live = liveBinding();
    long now = System.currentTimeMillis();
    inCall(live, OTHER_COUNSELLOR, now + 60_000);
    assertThat(
            service.endWhileSessionLocked(
                actor("other-counsellor-id", true), sessionConversation, "call", now, 5L))
        .isEqualTo(MEDIA);
    assertThat(live.getEndedAt()).isNotNull();
  }

  @Test
  void aColleagueWhoIsNotInTheCallOrSuspendedOrTheAskerDoesNotEndIt() {
    var live = liveBinding();
    long now = System.currentTimeMillis();
    // In the session room, but the membership in the call room ran out.
    inCall(live, OTHER_COUNSELLOR, now - 1);
    assertThat(
            service.endWhileSessionLocked(
                actor("other-counsellor-id", true), sessionConversation, "call", now, 5L))
        .isNull();
    inCall(live, OTHER_COUNSELLOR, now + 60_000);
    when(lifecycle.isActiveRecipient("other-counsellor-id")).thenReturn(false);
    assertThat(
            service.endWhileSessionLocked(
                actor("other-counsellor-id", true), sessionConversation, "call", now, 5L))
        .isNull();
    assertThat(
            service.endWhileSessionLocked(
                actor("asker-id", false), sessionConversation, "call", now, 5L))
        .isNull();
    assertThat(live.getEndedAt()).isNull();
  }

  @Test
  void endingACallWithoutABindingChangesNothing() {
    assertThat(
            service.endWhileSessionLocked(
                actor("counsellor-id", true), sessionConversation, "call", 1L, null))
        .isNull();
    verify(bindings, never()).lockById(any());
  }

  @Test
  void guestSeatsAreRevokedAfterTheEndThroughTheLifecycle() {
    service.revokeGuestsAfterEnd(MEDIA);
    verify(lifecycle).handleRoom(MEDIA, Map.of());
  }

  private static de.caritas.cob.userservice.api.model.MatrixCallObservation observation(
      long expiresAt) {
    return de.caritas.cob.userservice.api.model.MatrixCallObservation.builder()
        .origin(
            de.caritas.cob.userservice.api.model.MatrixCallObservation.ORIGIN_CREATOR_REGISTRATION)
        .sourceRoomId(SOURCE)
        .callId("call")
        .mediaRoomId(MEDIA)
        .creatorMatrixId(COUNSELLOR)
        .tenantId(TENANT)
        .sessionId(SESSION)
        .registeredAt(expiresAt - 600_000)
        .lastSeenAt(expiresAt - 600_000)
        .expiresAt(expiresAt)
        .build();
  }
}
