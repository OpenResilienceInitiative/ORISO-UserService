package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.CallRoomOrigin;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallObservation;
import de.caritas.cob.userservice.api.model.MatrixCallObservationEnd;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationEndRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService.Outcome;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatrixCallObservationServiceTest {
  private static final String SOURCE = "!group:example";
  private static final String MEDIA = "!media:example";
  private static final String CREATOR = "@creator:example";
  private static final String OTHER = "@other:example";
  private static final List<String> MEMBERS = List.of(CREATOR, OTHER);

  private final MatrixCallObservationRepository repo = mock(MatrixCallObservationRepository.class);
  private final MatrixCallObservationEndRepository ends =
      mock(MatrixCallObservationEndRepository.class);
  private final java.util.List<MatrixCallObservationEnd> storedEnds = new java.util.ArrayList<>();
  private final MatrixCallBindingRepository bindings = mock(MatrixCallBindingRepository.class);
  private final MatrixCallConversationResolver conversations =
      mock(MatrixCallConversationResolver.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final RecordingTransactionManager tx = new RecordingTransactionManager();
  private boolean guardTransactions;
  private final List<String> callsInsideTransaction = new java.util.ArrayList<>();
  private final org.mockito.listeners.InvocationListener transactionGuard =
      report -> {
        if (guardTransactions && tx.isOpen()) {
          callsInsideTransaction.add(report.getInvocation().toString());
        }
      };
  private final MatrixSynapseService matrix =
      mock(
          MatrixSynapseService.class,
          org.mockito.Mockito.withSettings().invocationListeners(transactionGuard));
  private final EventNotificationService notifications =
      mock(
          EventNotificationService.class,
          org.mockito.Mockito.withSettings().invocationListeners(transactionGuard));
  private final MatrixCallLifecycleService lifecycle = mock(MatrixCallLifecycleService.class);
  private final MatrixSessionCallBindingService sessionBindings =
      mock(MatrixSessionCallBindingService.class);
  private final java.util.List<MatrixCallObservation> saved = new java.util.ArrayList<>();
  private MatrixCallObservationService service;
  private AuthenticatedUser actor;

  @BeforeEach
  void setUp() {
    service =
        new MatrixCallObservationService(
            repo,
            ends,
            bindings,
            conversations,
            users,
            consultants,
            matrix,
            notifications,
            lifecycle,
            sessionBindings,
            tx);
    actor = new AuthenticatedUser();
    actor.setUserId("creator");
    actor.setTenantId(7L);
    var conversation = new MatrixCallConversation(SOURCE, 7L, null, 45L);
    when(conversations.resolveForWriting(SOURCE)).thenReturn(Optional.of(conversation));
    when(conversations.resolve(SOURCE)).thenReturn(Optional.of(conversation));
    when(conversations.lockCurrentForWriting(any())).thenReturn(true);
    var creatorUser = user("creator", CREATOR);
    var otherUser = user("other", OTHER);
    when(users.findByUserIdAndDeleteDateIsNull("creator")).thenReturn(Optional.of(creatorUser));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(CREATOR)).thenReturn(Optional.of(creatorUser));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(OTHER)).thenReturn(Optional.of(otherUser));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    when(lifecycle.isActiveRecipient(anyString())).thenReturn(true);
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(CREATOR, OTHER)));
    when(matrix.getCallRoomBinding(MEDIA, CREATOR))
        .thenReturn(Optional.of(Map.of("call_id", "call", "source_room_id", SOURCE)));
    when(matrix.getCallRoomOrigin(MEDIA, CREATOR))
        .thenReturn(Optional.of(new CallRoomOrigin(CREATOR, CREATOR)));
    when(repo.findForUpdateBySourceRoomIdAndCallId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(repo.findByMediaRoomId(anyString())).thenReturn(Optional.empty());
    when(repo.saveAndFlush(any()))
        .thenAnswer(
            i -> {
              saved.add(i.getArgument(0));
              return i.getArgument(0);
            });
    // The end table behaves like its unique key (source room, call, actor).
    when(ends.saveAndFlush(any()))
        .thenAnswer(
            i -> {
              storedEnds.add(i.getArgument(0));
              return i.getArgument(0);
            });
    when(ends.findForUpdateBySourceRoomIdAndCallIdAndActorMatrixId(
            anyString(), anyString(), anyString()))
        .thenAnswer(
            i ->
                storedEnds.stream()
                    .filter(e -> e.getSourceRoomId().equals(i.getArgument(0)))
                    .filter(e -> e.getCallId().equals(i.getArgument(1)))
                    .filter(e -> e.getActorMatrixId().equals(i.getArgument(2)))
                    .findFirst());
    when(ends.findFirstBySourceRoomIdAndCallIdOrderByEndedAtAsc(anyString(), anyString()))
        .thenAnswer(
            i ->
                storedEnds.stream()
                    .filter(e -> e.getSourceRoomId().equals(i.getArgument(0)))
                    .filter(e -> e.getCallId().equals(i.getArgument(1)))
                    .min(java.util.Comparator.comparingLong(MatrixCallObservationEnd::getEndedAt)));
  }

  @Test
  void creatorRegistrationStoresObservationDistinctFromInvitationAndNotifiesOthersOnly() {
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.OK);
    var row = saved.get(0);
    assertThat(row.getOrigin()).isEqualTo(MatrixCallObservation.ORIGIN_CREATOR_REGISTRATION);
    assertThat(row.getInvitedAt()).isNull();
    assertThat(row.getRegisteredAt()).isPositive();
    verify(notifications)
        .createCallInvitationNotification(
            any(), eq("other"), eq(false), eq("creator"), eq("call"), eq(MEDIA), eq(true));
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), eq("creator"), anyBoolean(), any(), any(), any(), anyBoolean());
  }

  @Test
  void aBindingCommittedWhileMatrixWasAskedIsSeenUnderTheLockAndWins() {
    // CodeRabbit 4562143169: the check runs again under the source lock, so an authoritative
    // binding written during the Matrix lookups is never pre-empted by this registration.
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(
            Optional.empty(),
            Optional.of(MatrixCallBinding.builder().sourceRoomId(SOURCE).build()));

    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    verify(repo, never()).saveAndFlush(any());
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), any(), anyBoolean(), any(), any(), any(), anyBoolean());
  }

  @Test
  void aCallThatAlreadyHasAnAuthoritativeBindingIsNotObserved() {
    // Observation and binding notifications share one dedupe key per (room, call). A member
    // must not be able to register a look-alike first and suppress the real call's entries.
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(MatrixCallBinding.builder().sourceRoomId(SOURCE).build()));

    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    verify(repo, never()).saveAndFlush(any());
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), any(), anyBoolean(), any(), any(), any(), anyBoolean());
  }

  @Test
  void callerOutsideTheSourceRoomIsRejected() {
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(OTHER)));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void mediaRoomCreatedByAnotherPersonIsRejected() {
    when(matrix.getCallRoomOrigin(MEDIA, CREATOR))
        .thenReturn(Optional.of(new CallRoomOrigin(OTHER, CREATOR)));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void bindingSentByAnotherPersonIsRejected() {
    when(matrix.getCallRoomOrigin(MEDIA, CREATOR))
        .thenReturn(Optional.of(new CallRoomOrigin(CREATOR, OTHER)));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
  }

  @Test
  void bindingForAnotherCallOrRoomIsRejected() {
    when(matrix.getCallRoomBinding(MEDIA, CREATOR))
        .thenReturn(Optional.of(Map.of("call_id", "x", "source_room_id", SOURCE)));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    when(matrix.getCallRoomBinding(MEDIA, CREATOR))
        .thenReturn(Optional.of(Map.of("call_id", "call", "source_room_id", "!elsewhere:x")));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
  }

  @Test
  void groupRegistrationStoresTheChatAndNoSession() {
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.OK);
    assertThat(saved.get(0).getChatId()).isEqualTo(45L);
    assertThat(saved.get(0).getSessionId()).isNull();
  }

  @Test
  void sessionCallIsObservedWithTheSessionAsOnlySource() {
    // #2237: a 1:1 session call invite is end-to-end encrypted, so no binding exists.
    var session = new MatrixCallConversation(SOURCE, 7L, 9L, null);
    when(conversations.resolveForWriting(SOURCE)).thenReturn(Optional.of(session));

    assertThat(service.register(actor, SOURCE, "call", MEDIA, false)).isEqualTo(Outcome.OK);

    var row = saved.get(0);
    assertThat(row.getSessionId()).isEqualTo(9L);
    assertThat(row.getChatId()).isNull();
    assertThat(row.getTenantId()).isEqualTo(7L);
    verify(conversations).lockCurrentForWriting(session);
    verify(notifications)
        .createCallInvitationNotification(
            eq(session), eq("other"), eq(false), eq("creator"), eq("call"), eq(MEDIA), eq(false));
  }

  @Test
  void sessionCallKeepsAllRegistrationChecks() {
    when(conversations.resolveForWriting(SOURCE))
        .thenReturn(Optional.of(new MatrixCallConversation(SOURCE, 7L, 9L, null)));
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(OTHER)));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(CREATOR, OTHER)));
    when(conversations.lockCurrentForWriting(any())).thenReturn(false); // rejected session
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    actor.setTenantId(8L);
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void sessionMemberReadsARunningSessionCall() {
    long now = System.currentTimeMillis();
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(sessionRow(9L, now - 1000, now + 60_000)));
    var state =
        service.readState(new MatrixCallConversation(SOURCE, 7L, 9L, null), "call", MEMBERS);
    assertThat(state.orElseThrow().state()).isEqualTo("running");
    assertThat(state.orElseThrow().callRoomId()).isEqualTo(MEDIA);
  }

  @Test
  void sessionRowIsNotReadableThroughAnotherSessionOrAChatWithTheSameId() {
    long now = System.currentTimeMillis();
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(sessionRow(9L, now - 1000, now + 60_000)));
    assertThat(
            service.readState(new MatrixCallConversation(SOURCE, 7L, 10L, null), "call", MEMBERS))
        .isEmpty();
    assertThat(service.readState(new MatrixCallConversation(SOURCE, 7L, null, 9L), "call", MEMBERS))
        .isEmpty();
  }

  @Test
  void groupRowIsNotReadableThroughASessionWithTheSameId() {
    long now = System.currentTimeMillis();
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(row(now - 1000, now + 60_000, null)));
    assertThat(
            service.readState(new MatrixCallConversation(SOURCE, 7L, 45L, null), "call", MEMBERS))
        .isEmpty();
    assertThat(
            service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call", MEMBERS))
        .isPresent();
  }

  @Test
  void anotherTenantIsRejected() {
    actor.setTenantId(8L);
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
  }

  @Test
  void expiredOrEndedRegistrationCannotBeRevived() {
    var row = row(1000, 2000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    assertThat(row.getExpiresAt()).isEqualTo(2000);
  }

  @Test
  void endedRegistrationCannotBeRevived() {
    long now = System.currentTimeMillis();
    var row = row(now - 1000, now + 60_000, now - 500);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    assertThat(row.getExpiresAt()).isEqualTo(now + 60_000);
  }

  @Test
  void liveRegistrationIsRenewedWithoutNewRowOrNotifications() {
    long now = System.currentTimeMillis();
    var row = row(now - 1000, now + 1_000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.OK);
    assertThat(row.getExpiresAt()).isGreaterThan(now + 60_000);
    verify(repo, never()).saveAndFlush(any());
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean());
  }

  @Test
  void rejectedRegistrationsNeverSaveOrNotify() {
    when(matrix.getCallRoomOrigin(MEDIA, CREATOR))
        .thenReturn(Optional.of(new CallRoomOrigin(CREATOR, OTHER)));
    service.register(actor, SOURCE, "call", MEDIA, true);
    actor.setTenantId(8L);
    service.register(actor, SOURCE, "call", MEDIA, true);
    verify(repo, never()).saveAndFlush(any());
    verify(repo, never()).save(any());
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean());
  }

  @Test
  void unknownAudienceDoesNotRecordTheEndSoARetryCanNotify() {
    long now = System.currentTimeMillis();
    var row = row(now - 1000, now + 60_000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.empty());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.end(actor, SOURCE, "call"))
        .isInstanceOf(MatrixSynapseService.CallLookupUnavailableException.class);
    assertThat(row.getEndedAt()).isNull();
  }

  @Test
  void endingTwiceNotifiesOnce() {
    long now = System.currentTimeMillis();
    var row = row(now - 1000, now + 60_000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);
    Long firstEnd = row.getEndedAt();
    assertThat(firstEnd).isNotNull();
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);
    assertThat(row.getEndedAt()).isEqualTo(firstEnd);
    verify(notifications, org.mockito.Mockito.times(1))
        .createCallEndedNotification(
            any(), eq("other"), eq(false), eq("call"), eq(MEDIA), anyLong(), anyLong());
  }

  @Test
  void inactiveRecipientsGetNoInvitation() {
    when(lifecycle.isActiveRecipient("other")).thenReturn(false);
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.OK);
    verify(lifecycle).isActiveRecipient("other");
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean());
  }

  @Test
  void expiredObservationReadsAsEndedNeverRunning() {
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(row(1000, 2000, null)));
    var state =
        service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call", MEMBERS);
    assertThat(state.orElseThrow().state()).isEqualTo("ended");
    assertThat(state.orElseThrow().endedAt()).isEqualTo(2000L);
  }

  @Test
  void liveObservationReadsRunning() {
    long now = System.currentTimeMillis();
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(row(now - 1000, now + 60_000, null)));
    var state =
        service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call", MEMBERS);
    assertThat(state.orElseThrow().state()).isEqualTo("running");
  }

  @Test
  void otherTenantRowIsNotReadable() {
    long now = System.currentTimeMillis();
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(row(now, now + 60_000, null)));
    assertThat(
            service.readState(new MatrixCallConversation(SOURCE, 9L, null, 45L), "call", MEMBERS))
        .isEmpty();
  }

  @Test
  void onlyTheCreatorEndsAndEndingNotifiesMembers() {
    long now = System.currentTimeMillis();
    var row = row(now - 1000, now + 60_000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    var stranger = new AuthenticatedUser();
    stranger.setUserId("other");
    stranger.setTenantId(7L);
    var otherUser = user("other", OTHER);
    when(users.findByUserIdAndDeleteDateIsNull("other")).thenReturn(Optional.of(otherUser));
    assertThat(service.end(stranger, SOURCE, "call")).isEqualTo(Outcome.NOT_FOUND);
    assertThat(row.getEndedAt()).isNull();
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);
    assertThat(row.getEndedAt()).isNotNull();
    verify(notifications)
        .createCallEndedNotification(
            any(), eq("other"), eq(false), eq("call"), eq(MEDIA), anyLong(), anyLong());
  }

  // --- FE#2209 review (CodeRabbit): the session binding ends with the observation ---

  @Test
  void aSessionCallsBindingEndsInTheObservationsTransactionEvenWhenNotifyingFails() {
    var session = new MatrixCallConversation(SOURCE, 7L, 16L, null);
    when(conversations.resolve(SOURCE)).thenReturn(Optional.of(session));
    long now = System.currentTimeMillis();
    var row = sessionRow(16L, now - 1000, now + 60_000);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(sessionBindings.boundCallId(SOURCE, "call")).thenReturn(Optional.of(5L));
    var order = new java.util.ArrayList<String>();
    org.mockito.Mockito.doAnswer(
            i -> {
              order.add("lock binding " + tx.isOpen());
              return null;
            })
        .when(sessionBindings)
        .lockBinding(5L);
    when(conversations.lockCurrentForWriting(any()))
        .thenAnswer(
            i -> {
              order.add("lock session " + tx.isOpen());
              return true;
            });
    when(sessionBindings.endWhileSessionLocked(any(), eq(session), eq("call"), anyLong(), eq(5L)))
        .thenAnswer(
            i -> {
              order.add("end binding " + tx.isOpen());
              return MEDIA;
            });
    org.mockito.Mockito.doThrow(new IllegalStateException("notification store down"))
        .when(notifications)
        .createCallEndedNotification(
            any(), anyString(), anyBoolean(), anyString(), anyString(), anyLong(), anyLong());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.end(actor, SOURCE, "call"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(order).containsExactly("lock binding true", "lock session true", "end binding true");
    assertThat(row.getEndedAt()).isNotNull();
    verify(sessionBindings).revokeGuestsAfterEnd(MEDIA);
  }

  @Test
  void aColleaguesEndOfASessionCallEndsTheBindingEvenWithoutAnObservationOfTheirOwn() {
    var session = new MatrixCallConversation(SOURCE, 7L, 16L, null);
    when(conversations.resolve(SOURCE)).thenReturn(Optional.of(session));
    long now = System.currentTimeMillis();
    // Registered by the creator; the colleague's end does not end the observation ...
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenReturn(Optional.of(sessionRow(16L, now - 1000, now + 60_000)));
    var colleague = new AuthenticatedUser();
    colleague.setUserId("other");
    colleague.setTenantId(7L);
    when(users.findByUserIdAndDeleteDateIsNull("other"))
        .thenReturn(Optional.of(user("other", OTHER)));
    // ... but the binding rule lets a counsellor who is in the call end the call.
    when(sessionBindings.endWhileSessionLocked(
            eq(colleague), eq(session), eq("call"), anyLong(), any()))
        .thenReturn(MEDIA);

    assertThat(service.end(colleague, SOURCE, "call")).isEqualTo(Outcome.OK);
    verify(sessionBindings).revokeGuestsAfterEnd(MEDIA);
  }

  // --- #1630 (a): no database transaction stays open while Matrix is called ---

  @Test
  void registrationCallsMatrixAndNotifiesOutsideAnyTransaction() {
    failInsideTransaction();
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.OK);
    assertThat(saved).hasSize(1);
    assertThat(tx.started()).isPositive();
    verify(notifications)
        .createCallInvitationNotification(
            any(), eq("other"), eq(false), eq("creator"), eq("call"), eq(MEDIA), eq(true));
  }

  @Test
  void endingCallsMatrixAndNotifiesOutsideAnyTransaction() {
    long now = System.currentTimeMillis();
    var row = row(now - 1000, now + 60_000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    failInsideTransaction();
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);
    assertThat(row.getEndedAt()).isNotNull();
    verify(notifications)
        .createCallEndedNotification(
            any(), eq("other"), eq(false), eq("call"), eq(MEDIA), anyLong(), anyLong());
  }

  // --- #1628 (2): ending wins over a registration that arrives late ---

  @Test
  void endBeforeTheRegistrationLandsIsStoredAndRejectsTheLateRegistration() {
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);

    assertThat(storedEnds).hasSize(1);
    var end = storedEnds.get(0);
    assertThat(end.getActorMatrixId()).isEqualTo(CREATOR);
    assertThat(end.getSourceRoomId()).isEqualTo(SOURCE);
    assertThat(end.getCallId()).isEqualTo("call");
    assertThat(end.getChatId()).isEqualTo(45L);
    assertThat(end.getSessionId()).isNull();
    assertThat(end.getEndedAt()).isPositive();
    verify(repo, never()).saveAndFlush(any());

    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    verify(repo, never()).saveAndFlush(any());
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean());
    verify(notifications, never())
        .createCallEndedNotification(
            any(), any(), anyBoolean(), any(), any(), anyLong(), anyLong());
  }

  @Test
  void theCreatorsCancellationAfterAnotherMembersEndIsStoredOnItsOwn() {
    assertThat(service.end(other(), SOURCE, "call")).isEqualTo(Outcome.OK);
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);

    assertThat(storedEnds)
        .extracting(MatrixCallObservationEnd::getActorMatrixId)
        .containsExactly(OTHER, CREATOR);
    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.NOT_FOUND);
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void aRepeatedEndOfTheSamePersonIsStoredOnce() {
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);
    assertThat(storedEnds).hasSize(1);
  }

  @Test
  void anEndLeftBySomebodyElseDoesNotBlockTheCreatorsRegistrationAndIsKept() {
    assertThat(service.end(other(), SOURCE, "call")).isEqualTo(Outcome.OK);

    assertThat(service.register(actor, SOURCE, "call", MEDIA, true)).isEqualTo(Outcome.OK);

    verify(repo, never()).delete(any());
    verify(ends, never()).delete(any());
    assertThat(saved).hasSize(1);
    assertThat(saved.get(0).getMediaRoomId()).isEqualTo(MEDIA);
    assertThat(storedEnds)
        .extracting(MatrixCallObservationEnd::getActorMatrixId)
        .containsExactly(OTHER);
  }

  @Test
  void theCreatorsEndOfARegisteredCallIsStoredToo() {
    var row = row(System.currentTimeMillis() - 1000, System.currentTimeMillis() + 60_000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));

    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);

    assertThat(row.getEndedAt()).isNotNull();
    assertThat(storedEnds)
        .extracting(MatrixCallObservationEnd::getActorMatrixId)
        .containsExactly(CREATOR);
  }

  @Test
  void anotherMembersEndOfARegisteredCallIsRefusedAndNotStored() {
    var row = row(System.currentTimeMillis() - 1000, System.currentTimeMillis() + 60_000, null);
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));

    assertThat(service.end(other(), SOURCE, "call")).isEqualTo(Outcome.NOT_FOUND);

    assertThat(row.getEndedAt()).isNull();
    assertThat(storedEnds).isEmpty();
  }

  @Test
  void onlyACurrentMemberOfTheConversationCanEndACallBeforeItsRegistration() {
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(OTHER)));
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.NOT_FOUND);
    verify(ends, never()).saveAndFlush(any());
  }

  @Test
  void aCallEndedBeforeItsRegistrationReadsAsEnded() {
    assertThat(service.end(actor, SOURCE, "call")).isEqualTo(Outcome.OK);
    var state =
        service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call", MEMBERS);
    assertThat(state.orElseThrow().state()).isEqualTo("ended");
    verify(matrix, never()).getCallRoomState(anyString(), anyString());
    // Never through another source that shares the room id's numbers.
    assertThat(
            service.readState(new MatrixCallConversation(SOURCE, 7L, 45L, null), "call", MEMBERS))
        .isEmpty();
  }

  private AuthenticatedUser other() {
    var other = new AuthenticatedUser();
    other.setUserId("other");
    other.setTenantId(7L);
    when(users.findByUserIdAndDeleteDateIsNull("other"))
        .thenReturn(Optional.of(user("other", OTHER)));
    return other;
  }

  // --- #1628 (1): a call stays known while anyone is still in it ---

  @Test
  void callStaysRunningWhileAnotherParticipantIsStillInItAfterTheCreatorWentOffline() {
    long now = System.currentTimeMillis();
    // The creator's browser stopped renewing 15 minutes ago; the lease ran out 5 minutes ago.
    var row = row(now - 20 * 60_000, now - 5 * 60_000, null);
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(matrix.getCallRoomMembers(MEDIA)).thenReturn(Optional.of(List.of(CREATOR, OTHER)));
    when(matrix.getCallRoomState(MEDIA, CREATOR))
        .thenReturn(
            Optional.of(
                List.of(departed(CREATOR, now - 15 * 60_000), active(OTHER, now - 18 * 60_000))));
    failInsideTransaction();

    var state =
        service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call", MEMBERS);

    assertThat(state.orElseThrow().state()).isEqualTo("running");
    assertThat(row.getExpiresAt()).isGreaterThan(now + 5 * 60_000);
    assertThat(row.getEndedAt()).isNull();
  }

  @Test
  void callEndsSoonAfterTheLastParticipantLeft() {
    long now = System.currentTimeMillis();
    var row = row(now - 20 * 60_000, now + 5 * 60_000, null);
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(matrix.getCallRoomMembers(MEDIA)).thenReturn(Optional.of(List.of(CREATOR, OTHER)));
    when(matrix.getCallRoomState(MEDIA, CREATOR))
        .thenReturn(
            Optional.of(List.of(departed(CREATOR, now - 90_000), departed(OTHER, now - 70_000))));
    failInsideTransaction();

    var state =
        service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call", MEMBERS);

    assertThat(state.orElseThrow().state()).isEqualTo("ended");
    assertThat(row.getEndedAt()).isNotNull().isLessThanOrEqualTo(System.currentTimeMillis());
    verify(notifications)
        .createCallEndedNotification(
            any(), eq("other"), eq(false), eq("call"), eq(MEDIA), anyLong(), anyLong());
  }

  @Test
  void aFreshCallIsNotEndedBeforeItsParticipantsCouldConnect() {
    long now = System.currentTimeMillis();
    var row = row(now - 70_000, now + 5 * 60_000, null);
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(matrix.getCallRoomMembers(MEDIA)).thenReturn(Optional.of(List.of(CREATOR)));
    when(matrix.getCallRoomState(MEDIA, CREATOR))
        .thenReturn(Optional.of(List.of(departed(CREATOR, now - 65_000))));

    var state =
        service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call", MEMBERS);

    assertThat(state.orElseThrow().state()).isEqualTo("running");
    assertThat(row.getEndedAt()).isNull();
  }

  @Test
  void unreadableOrUnknownMembershipNeverEndsACall() {
    long now = System.currentTimeMillis();
    var row = row(now - 20 * 60_000, now + 5 * 60_000, null);
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(matrix.getCallRoomMembers(MEDIA)).thenReturn(Optional.of(List.of(CREATOR)));
    // No membership event this server understands: inconclusive, not "everybody left".
    when(matrix.getCallRoomState(MEDIA, CREATOR))
        .thenReturn(Optional.of(List.of(Map.of("type", "m.room.create", "state_key", ""))));
    var conversation = new MatrixCallConversation(SOURCE, 7L, null, 45L);

    assertThat(service.readState(conversation, "call", MEMBERS).orElseThrow().state())
        .isEqualTo("running");
    when(matrix.getCallRoomState(MEDIA, CREATOR))
        .thenThrow(new MatrixSynapseService.CallLookupUnavailableException("down"));
    row.markLivenessChecked(0L);
    assertThat(service.readState(conversation, "call", MEMBERS).orElseThrow().state())
        .isEqualTo("running");
    assertThat(row.getEndedAt()).isNull();
  }

  @Test
  void membershipOfSomebodyOutsideTheConversationDoesNotKeepTheCallAlive() {
    long now = System.currentTimeMillis();
    var row = row(now - 20 * 60_000, now - 60_000, null);
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(matrix.getCallRoomMembers(MEDIA)).thenReturn(Optional.of(List.of(CREATOR, "@out:x")));
    when(matrix.getCallRoomState(MEDIA, CREATOR))
        .thenReturn(
            Optional.of(List.of(departed(CREATOR, now - 90_000), active("@out:x", now - 60_000))));

    var state =
        service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call", MEMBERS);

    assertThat(state.orElseThrow().state()).isEqualTo("ended");
  }

  @Test
  void aCallMemberEventOfAParticipantThatCannotBeReadIsNoProofTheyLeft() {
    long now = System.currentTimeMillis();
    var row = row(now - 20 * 60_000, now + 5 * 60_000, null);
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(matrix.getCallRoomMembers(MEDIA)).thenReturn(Optional.of(List.of(CREATOR, OTHER)));
    // OTHER's membership has content this server does not understand (no focus_active).
    Map<String, Object> unreadable =
        Map.of(
            "type",
            "org.matrix.msc3401.call.member",
            "event_id",
            "$unreadable",
            "sender",
            OTHER,
            "state_key",
            "_" + OTHER + "_DEVICE",
            "origin_server_ts",
            now - 60_000,
            "content",
            Map.of("application", "m.call", "scope", "m.room", "call_id", ""));
    when(matrix.getCallRoomState(MEDIA, CREATOR))
        .thenReturn(Optional.of(List.of(departed(CREATOR, now - 90_000), unreadable)));

    var state =
        service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call", MEMBERS);

    assertThat(state.orElseThrow().state()).isEqualTo("running");
    assertThat(row.getEndedAt()).isNull();
  }

  @Test
  void aLivenessVerdictOvertakenByANewerRenewalIsDiscarded() {
    long now = System.currentTimeMillis();
    // What this reader saw before it asked Matrix ...
    var seen = row(now - 20 * 60_000, now + 5 * 60_000, null);
    // ... and the row under the lock: the creator renewed in the meantime.
    var locked = row(now - 20 * 60_000, now + 5 * 60_000, null);
    locked.renew(now, now + 5 * 60_000);
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(seen));
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(locked));
    when(matrix.getCallRoomMembers(MEDIA)).thenReturn(Optional.of(List.of(CREATOR, OTHER)));
    when(matrix.getCallRoomState(MEDIA, CREATOR))
        .thenReturn(
            Optional.of(List.of(departed(CREATOR, now - 90_000), departed(OTHER, now - 70_000))));

    service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call", MEMBERS);

    assertThat(locked.getEndedAt()).isNull();
    verify(notifications, never())
        .createCallEndedNotification(
            any(), anyString(), anyBoolean(), anyString(), anyString(), anyLong(), anyLong());
  }

  @Test
  void livenessIsCheckedAtMostOncePerMinute() {
    long now = System.currentTimeMillis();
    var row = row(now - 20 * 60_000, now + 5 * 60_000, null);
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(matrix.getCallRoomMembers(MEDIA)).thenReturn(Optional.of(List.of(CREATOR, OTHER)));
    when(matrix.getCallRoomState(MEDIA, CREATOR))
        .thenReturn(Optional.of(List.of(active(OTHER, now - 60_000))));
    var conversation = new MatrixCallConversation(SOURCE, 7L, null, 45L);

    service.readState(conversation, "call", MEMBERS);
    service.readState(conversation, "call", MEMBERS);
    service.readState(conversation, "call", MEMBERS);

    verify(matrix, org.mockito.Mockito.times(1)).getCallRoomState(MEDIA, CREATOR);
  }

  @Test
  void anotherParticipantReadsTheMediaRoomWhenTheCreatorLeftIt() {
    long now = System.currentTimeMillis();
    var row = row(now - 20 * 60_000, now - 60_000, null);
    when(repo.findBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(repo.findForUpdateBySourceRoomIdAndCallId(SOURCE, "call")).thenReturn(Optional.of(row));
    when(matrix.getCallRoomMembers(MEDIA)).thenReturn(Optional.of(List.of(OTHER)));
    when(matrix.getCallRoomState(MEDIA, OTHER))
        .thenReturn(Optional.of(List.of(active(OTHER, now - 60_000))));

    var state =
        service.readState(new MatrixCallConversation(SOURCE, 7L, null, 45L), "call", MEMBERS);

    assertThat(state.orElseThrow().state()).isEqualTo("running");
    verify(matrix, never()).getCallRoomState(MEDIA, CREATOR);
  }

  /** Every later Matrix or notification call must happen with no transaction open. */
  private void failInsideTransaction() {
    guardTransactions = true;
  }

  @org.junit.jupiter.api.AfterEach
  void noCallInsideATransaction() {
    assertThat(callsInsideTransaction).isEmpty();
  }

  private Map<String, Object> active(String sender, long since) {
    return Map.of(
        "type",
        "org.matrix.msc3401.call.member",
        "event_id",
        "$" + sender + since,
        "sender",
        sender,
        "state_key",
        "_" + sender + "_DEVICE",
        "origin_server_ts",
        since,
        "content",
        Map.of(
            "application",
            "m.call",
            "scope",
            "m.room",
            "call_id",
            "",
            "device_id",
            "DEVICE",
            "focus_active",
            Map.of("type", "livekit"),
            "foci_preferred",
            List.of(),
            "created_ts",
            since,
            "expires",
            4 * 60 * 60 * 1000L));
  }

  private Map<String, Object> departed(String sender, long at) {
    return Map.of(
        "type",
        "org.matrix.msc3401.call.member",
        "event_id",
        "$" + sender + at,
        "sender",
        sender,
        "state_key",
        "_" + sender + "_DEVICE",
        "origin_server_ts",
        at,
        "content",
        Map.of());
  }

  private static MatrixCallObservation row(long registered, long expires, Long ended) {
    return MatrixCallObservation.builder()
        .origin(MatrixCallObservation.ORIGIN_CREATOR_REGISTRATION)
        .sourceRoomId(SOURCE)
        .callId("call")
        .mediaRoomId(MEDIA)
        .creatorMatrixId(CREATOR)
        .tenantId(7L)
        .chatId(45L)
        .registeredAt(registered)
        .lastSeenAt(registered)
        .expiresAt(expires)
        .endedAt(ended)
        .build();
  }

  private static MatrixCallObservation sessionRow(long sessionId, long registered, long expires) {
    return MatrixCallObservation.builder()
        .origin(MatrixCallObservation.ORIGIN_CREATOR_REGISTRATION)
        .sourceRoomId(SOURCE)
        .callId("call")
        .mediaRoomId(MEDIA)
        .creatorMatrixId(CREATOR)
        .tenantId(7L)
        .sessionId(sessionId)
        .registeredAt(registered)
        .lastSeenAt(registered)
        .expiresAt(expires)
        .build();
  }

  private static User user(String id, String matrixId) {
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@example.invalid")
        .tenantId(7L)
        .matrixUserId(matrixId)
        .build();
  }
}
