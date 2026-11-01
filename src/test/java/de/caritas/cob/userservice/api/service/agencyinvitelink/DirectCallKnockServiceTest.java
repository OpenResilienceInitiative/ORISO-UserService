package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Via;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService.CounsellorCall;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService.Outcome;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectCallKnockServiceTest {

  private static final long SESSION_ID = 500L;
  private static final long TENANT = 7L;

  @Mock private GroupChatJoinRequestRepository repository;
  @Mock private SessionRepository sessionRepository;

  @Mock
  private de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository inviteLinkRepository;

  @Mock private de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository callBindings;

  @Mock private de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService callGuests;
  @Mock private de.caritas.cob.userservice.api.port.out.UserRepository users;
  @Mock private de.caritas.cob.userservice.api.port.out.ConsultantRepository consultants;

  @Mock
  private de.caritas.cob.userservice.api.service.notification.EventNotificationService
      eventNotifications;

  private DirectCallKnockService service;
  private Consultant counsellor;
  private User asker;
  private Session session;

  @BeforeEach
  void setUp() {
    service =
        new DirectCallKnockService(
            repository,
            sessionRepository,
            inviteLinkRepository,
            callBindings,
            callGuests,
            users,
            consultants,
            eventNotifications);
    counsellor = consultant("counsellor-1");
    when(callBindings.findById(3L)).thenReturn(Optional.of(binding()));
    asker = new User();
    asker.setUserId("asker-1");
    session = new Session();
    session.setId(SESSION_ID);
    session.setConsultant(counsellor);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(sessionRepository.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(session));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(repository.findSessionIdById(any())).thenReturn(Optional.of(SESSION_ID));
  }

  @Test
  void knock_Should_CreatePendingDirectLinkKnock() {
    var knock = service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO);

    assertThat(knock.getVia()).isEqualTo(Via.DIRECT_LINK);
    assertThat(knock.getStatus()).isEqualTo(Status.PENDING);
    assertThat(knock.getSessionId()).isEqualTo(SESSION_ID);
    assertThat(knock.getUserId()).isEqualTo("asker-1");
    assertThat(knock.getInviteLinkId()).isEqualTo(9L);
    assertThat(knock.getMedium()).isEqualTo("VIDEO");
    // Never part of the Matrix admission path of the self-help groups.
    assertThat(knock.getSeriesId()).isNull();
    assertThat(knock.getAdmissionRequestedAt()).isNull();
  }

  /** FE#1952: a knock nobody hears is a knock nobody answers. */
  @Test
  void knock_Should_TellTheCounsellor_When_ANewKnockOpens() {
    var knock = service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO);

    verify(eventNotifications).createCallKnockRequestedNotification(session, knock);
  }

  /**
   * The entry is written in its own transaction. Written before the knock commits, a rolled-back
   * knock would still ring the counsellor for a person who is not waiting.
   */
  @Test
  void knock_Should_TellTheCounsellorOnlyAfterTheKnockCommits() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      var knock = service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO);

      verify(eventNotifications, never()).createCallKnockRequestedNotification(any(), any());
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
      verify(eventNotifications).createCallKnockRequestedNotification(session, knock);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void knock_Should_NotTellTheCounsellor_When_TheKnockRollsBack() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO);

      TransactionSynchronizationManager.getSynchronizations()
          .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
      verify(eventNotifications, never()).createCallKnockRequestedNotification(any(), any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void knock_Should_KeepTheKnock_When_TheNotificationFails() {
    org.mockito.Mockito.doThrow(new IllegalStateException("feed down"))
        .when(eventNotifications)
        .createCallKnockRequestedNotification(any(), any());
    TransactionSynchronizationManager.initSynchronization();
    try {
      var knock = service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO);

      assertThatCode(
              () ->
                  TransactionSynchronizationManager.getSynchronizations()
                      .forEach(TransactionSynchronization::afterCommit))
          .doesNotThrowAnyException();
      verify(eventNotifications).createCallKnockRequestedNotification(session, knock);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void knock_Should_NotTellTheCounsellorAgain_When_ThePersonKnocksAgain() {
    var open = pending(1L, LocalDateTime.now().minusMinutes(5));
    when(repository.findPendingCallKnocksForUpdate(SESSION_ID, "asker-1", Via.DIRECT_LINK))
        .thenReturn(List.of(open));

    service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.AUDIO);

    verify(eventNotifications, never()).createCallKnockRequestedNotification(any(), any());
  }

  @Test
  void knock_Should_NotTellTheCounsellor_When_ThePersonWasLetInBefore() {
    var admitted = pending(1L, LocalDateTime.now().minusDays(3));
    admitted.setStatus(Status.ADMITTED);
    when(repository.findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
            SESSION_ID, "asker-1", Via.DIRECT_LINK, Status.ADMITTED))
        .thenReturn(Optional.of(admitted));

    service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO);

    verify(eventNotifications, never()).createCallKnockRequestedNotification(any(), any());
  }

  @Test
  void knock_Should_LockTheConversationBeforeLookingForAnOpenKnock() {
    service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO);

    var order = inOrder(sessionRepository, repository);
    order.verify(sessionRepository).findByIdForUpdate(SESSION_ID);
    order.verify(repository).findPendingCallKnocksForUpdate(SESSION_ID, "asker-1", Via.DIRECT_LINK);
  }

  @Test
  void knock_Should_ReturnOpenKnock_When_PersonKnocksAgain() {
    var open = pending(1L, LocalDateTime.now().minusMinutes(5));
    when(repository.findPendingCallKnocksForUpdate(SESSION_ID, "asker-1", Via.DIRECT_LINK))
        .thenReturn(List.of(open));

    assertThat(service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.AUDIO)).isSameAs(open);
    verify(repository, never()).save(any());
  }

  @Test
  void knock_Should_StartAfresh_When_TheOpenKnockIsStale() {
    var stale = pending(1L, LocalDateTime.now().minusHours(3));
    when(repository.findPendingCallKnocksForUpdate(SESSION_ID, "asker-1", Via.DIRECT_LINK))
        .thenReturn(List.of(stale));

    var knock = service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.AUDIO);

    assertThat(knock.getId()).isNull();
    // Superseded: an old waiting-room view can no longer admit it, so the policy and the
    // conversation endpoints always judge the same, latest knock.
    assertThat(stale.getStatus()).isEqualTo(Status.CANCELLED);
    verify(repository, org.mockito.Mockito.times(2)).save(any());
    // The fresh knock is a new knock: the counsellor hears it.
    verify(eventNotifications).createCallKnockRequestedNotification(session, knock);
  }

  @Test
  void knock_Should_ReturnTheAdmission_When_ThePersonWasLetInBefore() {
    var admitted = pending(1L, LocalDateTime.now().minusDays(3));
    admitted.setStatus(Status.ADMITTED);
    when(repository.findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
            SESSION_ID, "asker-1", Via.DIRECT_LINK, Status.ADMITTED))
        .thenReturn(Optional.of(admitted));

    // Let in once holds for the conversation, also long after the waiting window.
    assertThat(service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO)).isSameAs(admitted);
    verify(repository, never()).save(any());
  }

  @Test
  void knock_Should_OpenANewKnock_When_TheLastOneWasDeclined() {
    when(repository.findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
            any(), any(), any(), any()))
        .thenReturn(Optional.empty());

    var knock = service.knock(SESSION_ID, asker, 9L, InviteLinkChatType.VIDEO);

    assertThat(knock.getStatus()).isEqualTo(Status.PENDING);
    verify(repository).save(any());
  }

  @Test
  void ownFor_Should_PreferTheAdmission_ThenTheLatestKnock() {
    var admitted = pending(1L, LocalDateTime.now());
    admitted.setStatus(Status.ADMITTED);
    var latest = pending(2L, LocalDateTime.now());
    when(repository.findFirstBySessionIdAndUserIdAndViaOrderByIdDesc(
            SESSION_ID, "asker-1", Via.DIRECT_LINK))
        .thenReturn(Optional.of(latest));

    assertThat(service.ownFor(SESSION_ID, asker)).isSameAs(latest);

    when(repository.findFirstBySessionIdAndUserIdAndViaAndStatusOrderByIdDesc(
            SESSION_ID, "asker-1", Via.DIRECT_LINK, Status.ADMITTED))
        .thenReturn(Optional.of(admitted));
    assertThat(service.ownFor(SESSION_ID, asker)).isSameAs(admitted);

    var stranger = new User();
    stranger.setUserId("asker-2");
    assertThatThrownBy(() -> service.ownFor(SESSION_ID, stranger))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void admit_And_Decline_Should_BeTheConversationsCounsellorsDecision() {
    var knock = pending(1L, LocalDateTime.now());
    when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(knock));

    var admitted = service.admit(1L, counsellor);

    assertThat(admitted.getStatus()).isEqualTo(Status.ADMITTED);
    assertThat(admitted.getDecidedBy()).isEqualTo("counsellor-1");
    assertThat(admitted.getDecidedAt()).isNotNull();
    // Idempotent.
    assertThat(service.admit(1L, counsellor).getStatus()).isEqualTo(Status.ADMITTED);
    assertThatThrownBy(() -> service.decline(1L, counsellor)).isInstanceOf(ConflictException.class);
  }

  /**
   * Same lock order as knock (conversation, then knock): an admission can never slip between
   * knock's admission check and its pending lookup and leave a newer pending knock behind.
   */
  @Test
  void decideAndAdmitAll_Should_LockTheConversationBeforeTheKnocks() {
    when(repository.findByIdForUpdate(1L))
        .thenReturn(Optional.of(pending(1L, LocalDateTime.now())));

    service.admit(1L, counsellor);
    var order = inOrder(sessionRepository, repository);
    order.verify(sessionRepository).findByIdForUpdate(SESSION_ID);
    order.verify(repository).findByIdForUpdate(1L);
    // The knock entity is not loaded unlocked first, so no stale copy can be decided.
    verify(repository, never()).findById(any());

    service.admitAll(counsellor, SESSION_ID, null);
    order.verify(sessionRepository).findByIdForUpdate(SESSION_ID);
    order
        .verify(repository)
        .findCallKnocksForConsultantForUpdate(
            eq("counsellor-1"), eq(Via.DIRECT_LINK), eq(Status.PENDING), any(), eq(SESSION_ID));
  }

  @Test
  void admit_Should_Return404_ForAnotherCounsellor_AndForSeriesKnocks() {
    when(repository.findByIdForUpdate(1L))
        .thenReturn(Optional.of(pending(1L, LocalDateTime.now())));
    assertThatThrownBy(() -> service.admit(1L, consultant("someone-else")))
        .isInstanceOf(NotFoundException.class);

    var seriesKnock =
        GroupChatJoinRequest.builder()
            .id(2L)
            .via(Via.INVITE_LINK)
            .seriesId(77L)
            .consultantId("c")
            .status(Status.PENDING)
            .requestedAt(LocalDateTime.now())
            .build();
    when(repository.findByIdForUpdate(2L)).thenReturn(Optional.of(seriesKnock));
    assertThatThrownBy(() -> service.admit(2L, counsellor)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void admitAll_Should_LetEveryoneWaitingIn() {
    var first = pending(1L, LocalDateTime.now().minusMinutes(2));
    var second = pending(2L, LocalDateTime.now().minusMinutes(1));
    when(repository.findCallKnocksForConsultantForUpdate(
            eq("counsellor-1"), eq(Via.DIRECT_LINK), eq(Status.PENDING), any(), eq(SESSION_ID)))
        .thenReturn(List.of(first, second));

    var admitted = service.admitAll(counsellor, SESSION_ID, null);

    assertThat(admitted)
        .extracting(GroupChatJoinRequest::getStatus)
        .containsExactly(Status.ADMITTED, Status.ADMITTED);
  }

  @Test
  void admitAll_Should_RefuseWithoutTheCurrentCall() {
    assertThatThrownBy(() -> service.admitAll(counsellor, null, null))
        .isInstanceOf(BadRequestException.class);
    verify(repository, never())
        .findCallKnocksForConsultantForUpdate(any(), any(), any(), any(), any());
  }

  @Test
  void pendingFor_Should_ScopeToTheCallRoom_And_KeepTheUnscopedList() {
    var session = new Session();
    session.setId(SESSION_ID);
    when(sessionRepository.findByMatrixRoomId("!room:oriso")).thenReturn(Optional.of(session));

    service.pendingFor(counsellor, null, "!room:oriso");
    verify(repository)
        .findCallKnocksForConsultant(
            eq("counsellor-1"), eq(Via.DIRECT_LINK), eq(Status.PENDING), any(), eq(SESSION_ID));

    service.pendingFor(counsellor, null, null);
    verify(repository)
        .findCallKnocksForConsultant(
            eq("counsellor-1"), eq(Via.DIRECT_LINK), eq(Status.PENDING), any(), eq(null));
  }

  @Test
  void scope_Should_RejectAContradictionAndAnUnknownRoom() {
    var other = new Session();
    other.setId(SESSION_ID + 1);
    when(sessionRepository.findByMatrixRoomId("!other:oriso")).thenReturn(Optional.of(other));

    assertThatThrownBy(() -> service.pendingFor(counsellor, SESSION_ID, "!other:oriso"))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.pendingFor(counsellor, null, "!unknown:oriso"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void ownKnock_Should_BeReadableAndCancellableOnlyByThePerson() {
    var knock = pending(1L, LocalDateTime.now());
    when(repository.findById(1L)).thenReturn(Optional.of(knock));
    when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(knock));

    assertThat(service.getOwn(1L, asker)).isSameAs(knock);
    assertThat(service.cancelOwn(1L, asker).getStatus()).isEqualTo(Status.CANCELLED);
    // Idempotent; a decided knock stays as it is.
    assertThat(service.cancelOwn(1L, asker).getStatus()).isEqualTo(Status.CANCELLED);

    var stranger = new User();
    stranger.setUserId("asker-2");
    assertThatThrownBy(() -> service.getOwn(1L, stranger)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.cancelOwn(1L, stranger)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void cancelOwn_Should_ReadTheKnockUnderLock_AndKeepAnAdmission() {
    var admitted = pending(1L, LocalDateTime.now());
    admitted.setStatus(Status.ADMITTED);
    when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(admitted));

    assertThat(service.cancelOwn(1L, asker).getStatus()).isEqualTo(Status.ADMITTED);
    verify(repository, never()).findById(any());
    verify(repository, never()).save(any());
  }

  // --- Call share links (#1487) -------------------------------------------------------------

  @Test
  void knockOnCallLink_Should_CreateACallLinkKnock_OnTheLinkNotTheConversation() {
    var knock = service.knockOnCallLink(5L, asker, "AUDIO");

    assertThat(knock.getVia()).isEqualTo(Via.CALL_LINK);
    assertThat(knock.getInviteLinkId()).isEqualTo(5L);
    assertThat(knock.getUserId()).isEqualTo("asker-1");
    assertThat(knock.getStatus()).isEqualTo(Status.PENDING);
    assertThat(knock.getMedium()).isEqualTo("AUDIO");
    // The guest must not learn the conversation of the call (security review, #1487).
    assertThat(knock.getSessionId()).isNull();
  }

  @Test
  void knockOnCallLink_Should_ReturnTheOpenKnock_When_TheGuestKnocksAgain() {
    var open = callLinkKnock(1L, Status.PENDING);
    when(repository.findFirstByInviteLinkIdAndUserIdAndViaAndStatusOrderByIdDesc(
            5L, "asker-1", Via.CALL_LINK, Status.PENDING))
        .thenReturn(Optional.of(open));

    assertThat(service.knockOnCallLink(5L, asker, "AUDIO")).isSameAs(open);
    verify(repository, never()).save(any());
  }

  /** Seating and a counsellor's decline serialise on the admission row (#1487 review). */
  @Test
  void callLinkAdmission_Should_LockTheAdmission() {
    var admitted = callLinkKnock(1L, Status.ADMITTED);
    when(repository.findCallLinkKnocksForUpdate(
            5L, "asker-1", Via.CALL_LINK, Status.ADMITTED, PageRequest.of(0, 1)))
        .thenReturn(java.util.List.of(admitted));

    assertThat(service.callLinkAdmission(5L, asker)).containsSame(admitted);
    verify(repository, never())
        .findFirstByInviteLinkIdAndUserIdAndViaAndStatusOrderByIdDesc(any(), any(), any(), any());
  }

  @Test
  void admit_Should_LetTheLinkOwnerDecideACallLinkKnock() {
    var knock = callLinkKnock(1L, Status.PENDING);
    when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(knock));
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(callLink("counsellor-1")));
    inCall(counsellor, Outcome.ADMITTED);

    assertThat(service.admit(1L, counsellor).getStatus()).isEqualTo(Status.ADMITTED);
  }

  @Test
  void admit_Should_Return404_ForACallLinkKnock_When_TheCounsellorIsNotInTheCall() {
    var knock = callLinkKnock(1L, Status.PENDING);
    when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(knock));
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(callLink("someone-else")));
    inCall(counsellor, Outcome.NOT_FOUND);

    assertThatThrownBy(() -> service.admit(1L, counsellor)).isInstanceOf(NotFoundException.class);
    assertThat(knock.getStatus()).isEqualTo(Status.PENDING);
  }

  @Test
  void admit_Should_Return404_ForACallLinkKnockOfAnExpiredLink() {
    var knock = callLinkKnock(1L, Status.PENDING);
    when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(knock));
    var expired = callLink("counsellor-1");
    expired.setStatus("EXPIRED");
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(expired));
    inCall(counsellor, Outcome.ADMITTED);

    assertThatThrownBy(() -> service.admit(1L, counsellor)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void decline_Should_RevokeAnAdmittedCallLinkGuest_AndTakeTheSeatBack() {
    var knock = callLinkKnock(1L, Status.ADMITTED);
    when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(knock));
    var link = callLink("counsellor-1");
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(link));
    var binding = binding();
    when(callBindings.findById(3L)).thenReturn(Optional.of(binding));
    inCall(counsellor, Outcome.ADMITTED);
    asker.setMatrixUserId("@asker:example");
    when(users.findByUserIdAndDeleteDateIsNull("asker-1")).thenReturn(Optional.of(asker));
    counsellor.setMatrixUserId("@counsellor:example");
    when(consultants.findByIdAndDeleteDateIsNull("counsellor-1"))
        .thenReturn(Optional.of(counsellor));

    assertThat(service.decline(1L, counsellor).getStatus()).isEqualTo(Status.DECLINED);
    verify(callGuests).revokeSeat(binding, "@asker:example", "@counsellor:example");
  }

  @Test
  void cancelAdmissionsOnCallLink_Should_EndEarlierAdmissionsForRejoins() {
    var admitted = callLinkKnock(1L, Status.ADMITTED);
    when(repository.findByInviteLinkIdAndViaAndStatus(5L, Via.CALL_LINK, Status.ADMITTED))
        .thenReturn(List.of(admitted));

    service.cancelAdmissionsOnCallLink(5L);
    assertThat(admitted.getStatus()).isEqualTo(Status.CANCELLED);
    verify(callGuests, never()).revokeSeat(any(), any(), any());
  }

  @Test
  void declineGuestOnCallLink_Should_DeclineOpenAndGrantedKnocksOfThatGuest() {
    var pending = callLinkKnock(1L, Status.PENDING);
    var admitted = callLinkKnock(2L, Status.ADMITTED);
    var cancelled = callLinkKnock(3L, Status.CANCELLED);
    when(repository.findByInviteLinkIdAndUserIdAndVia(5L, "asker-1", Via.CALL_LINK))
        .thenReturn(List.of(pending, admitted, cancelled));

    service.declineGuestOnCallLink(5L, "asker-1", "counsellor-1");

    assertThat(pending.getStatus()).isEqualTo(Status.DECLINED);
    assertThat(admitted.getStatus()).isEqualTo(Status.DECLINED);
    assertThat(cancelled.getStatus()).isEqualTo(Status.CANCELLED);
  }

  @Test
  void pendingFor_Should_ListDirectAndCallLinkKnocksInTheOrderTheyCameIn() {
    var direct = pending(1L, LocalDateTime.now().minusMinutes(2));
    var viaLink = callLinkKnock(2L, Status.PENDING);
    viaLink.setRequestedAt(LocalDateTime.now().minusMinutes(5));
    when(repository.findCallKnocksForConsultant(
            eq("counsellor-1"), eq(Via.DIRECT_LINK), eq(Status.PENDING), any(), eq(null)))
        .thenReturn(List.of(direct));
    when(repository.findCallLinkKnocksForConsultant(
            eq("counsellor-1"), eq(Via.CALL_LINK), eq(Status.PENDING), any(), eq(null)))
        .thenReturn(List.of(viaLink));
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(callLink("counsellor-1")));
    inCall(counsellor, Outcome.ADMITTED);

    assertThat(service.pendingFor(counsellor, null, null)).containsExactly(viaLink, direct);
  }

  @Test
  void pendingFor_Should_FindCallLinkKnocksOfTheFilteredCall() {
    var session = new Session();
    session.setId(SESSION_ID);
    session.setMatrixRoomId("!room:oriso");
    when(sessionRepository.findByMatrixRoomId("!room:oriso")).thenReturn(Optional.of(session));
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    var viaLink = callLinkKnock(2L, Status.PENDING);
    when(repository.findCallLinkKnocksForConsultant(
            eq("counsellor-1"), eq(Via.CALL_LINK), eq(Status.PENDING), any(), eq("!room:oriso")))
        .thenReturn(List.of(viaLink));
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(callLink("counsellor-1")));
    inCall(counsellor, Outcome.ADMITTED);

    assertThat(service.pendingFor(counsellor, null, "!room:oriso")).containsExactly(viaLink);
    assertThat(service.pendingFor(counsellor, SESSION_ID, null)).containsExactly(viaLink);
  }

  @Test
  void pendingFor_Should_ScopeAGroupCallRoomToItsCallLinkKnocksOnly() {
    when(callBindings.existsBySourceRoomId("!group:oriso")).thenReturn(true);
    var viaLink = callLinkKnock(2L, Status.PENDING);
    when(repository.findCallLinkKnocksForConsultant(
            eq("counsellor-1"), eq(Via.CALL_LINK), eq(Status.PENDING), any(), eq("!group:oriso")))
        .thenReturn(List.of(viaLink));
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(callLink("counsellor-1")));
    inCall(counsellor, Outcome.ADMITTED);

    assertThat(service.pendingFor(counsellor, null, "!group:oriso")).containsExactly(viaLink);
    verify(repository, never()).findCallKnocksForConsultant(any(), any(), any(), any(), any());
  }

  @Test
  void admitAll_Should_AdmitTheCallLinkKnocksOfTheCurrentCallOnly() {
    var session = new Session();
    session.setId(SESSION_ID);
    session.setMatrixRoomId("!room:oriso");
    when(sessionRepository.findByMatrixRoomId("!room:oriso")).thenReturn(Optional.of(session));
    var viaLink = callLinkKnock(2L, Status.PENDING);
    when(repository.findCallLinkKnocksForConsultantForUpdate(
            eq("counsellor-1"), eq(Via.CALL_LINK), eq(Status.PENDING), any(), eq("!room:oriso")))
        .thenReturn(List.of(viaLink));
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(callLink("counsellor-1")));
    inCall(counsellor, Outcome.ADMITTED);

    assertThat(service.admitAll(counsellor, null, "!room:oriso")).containsExactly(viaLink);
    assertThat(viaLink.getStatus()).isEqualTo(Status.ADMITTED);
    verify(repository, never())
        .findCallLinkKnocksForConsultantForUpdate(any(), any(), any(), any(), eq(null));
  }

  // Frank 2026-10-28: a knock on a call share link reaches every counsellor in that call.

  @Test
  void pendingFor_Should_ShowACallLinkKnockToACoModeratorInTheCall() {
    var coModerator = consultant("co-moderator");
    when(callBindings.existsBySourceRoomId("!room:oriso")).thenReturn(true);
    var viaLink = callLinkKnock(2L, Status.PENDING);
    when(repository.findCallLinkKnocksForConsultant(
            eq("co-moderator"), eq(Via.CALL_LINK), eq(Status.PENDING), any(), eq("!room:oriso")))
        .thenReturn(List.of(viaLink));
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(callLink("counsellor-1")));
    inCall(coModerator, Outcome.ADMITTED);

    assertThat(service.pendingFor(coModerator, null, "!room:oriso")).containsExactly(viaLink);
  }

  @Test
  void pendingFor_Should_HideACallLinkKnock_FromACounsellorNotInTheCall_AndWhenMatrixIsDown() {
    var outsider = consultant("outsider");
    when(callBindings.existsBySourceRoomId("!room:oriso")).thenReturn(true);
    when(repository.findCallLinkKnocksForConsultant(
            eq("outsider"), eq(Via.CALL_LINK), eq(Status.PENDING), any(), eq("!room:oriso")))
        .thenReturn(List.of(callLinkKnock(2L, Status.PENDING)));
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(callLink("counsellor-1")));

    inCall(outsider, Outcome.NOT_FOUND);
    assertThat(service.pendingFor(outsider, null, "!room:oriso")).isEmpty();

    inCall(outsider, Outcome.MATRIX_UNAVAILABLE);
    assertThat(service.pendingFor(outsider, null, "!room:oriso")).isEmpty();
  }

  @Test
  void pendingFor_Should_HideAKnockOnALinkOfAnotherTraeger() {
    var coModerator = consultant("co-moderator");
    when(callBindings.existsBySourceRoomId("!room:oriso")).thenReturn(true);
    when(repository.findCallLinkKnocksForConsultant(
            eq("co-moderator"), eq(Via.CALL_LINK), eq(Status.PENDING), any(), eq("!room:oriso")))
        .thenReturn(List.of(callLinkKnock(2L, Status.PENDING)));
    var foreign = callLink("counsellor-1");
    foreign.setTenantId(99L);
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(foreign));
    inCall(coModerator, Outcome.ADMITTED);

    assertThat(service.pendingFor(coModerator, null, "!room:oriso")).isEmpty();
  }

  @Test
  void admit_And_Decline_Should_LetACoModeratorInTheCallDecideACallLinkKnock() {
    var coModerator = consultant("co-moderator");
    var first = callLinkKnock(1L, Status.PENDING);
    var second = callLinkKnock(2L, Status.PENDING);
    when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
    when(repository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(callLink("counsellor-1")));
    inCall(coModerator, Outcome.ADMITTED);

    var admitted = service.admit(1L, coModerator);
    var declined = service.decline(2L, coModerator);

    assertThat(admitted.getStatus()).isEqualTo(Status.ADMITTED);
    assertThat(admitted.getDecidedBy()).isEqualTo("co-moderator");
    assertThat(declined.getStatus()).isEqualTo(Status.DECLINED);
  }

  @Test
  void admit_Should_Return404_ForTheLinkOwner_When_TheyLeftTheCall() {
    var knock = callLinkKnock(1L, Status.PENDING);
    when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(knock));
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(callLink("counsellor-1")));
    inCall(counsellor, Outcome.NOT_FOUND);

    assertThatThrownBy(() -> service.admit(1L, counsellor)).isInstanceOf(NotFoundException.class);
    assertThat(knock.getStatus()).isEqualTo(Status.PENDING);
  }

  @Test
  void admitAll_Should_LetACoModeratorAdmitOnlyTheKnocksOfCallsTheyAreIn() {
    var coModerator = consultant("co-moderator");
    when(callBindings.existsBySourceRoomId("!room:oriso")).thenReturn(true);
    var mine = callLinkKnock(2L, Status.PENDING);
    var other = callLinkKnock(3L, Status.PENDING);
    other.setInviteLinkId(6L);
    when(repository.findCallLinkKnocksForConsultantForUpdate(
            eq("co-moderator"), eq(Via.CALL_LINK), eq(Status.PENDING), any(), eq("!room:oriso")))
        .thenReturn(List.of(mine, other));
    when(inviteLinkRepository.findById(5L)).thenReturn(Optional.of(callLink("counsellor-1")));
    var otherLink = callLink("counsellor-1");
    otherLink.setId(6L);
    otherLink.setCallBindingId(4L);
    when(inviteLinkRepository.findById(6L)).thenReturn(Optional.of(otherLink));
    when(callBindings.findById(4L))
        .thenReturn(
            Optional.of(
                de.caritas.cob.userservice.api.model.MatrixCallBinding.builder()
                    .id(4L)
                    .sourceRoomId("!room:oriso")
                    .callId("call-2")
                    .build()));
    inCall(coModerator, Outcome.ADMITTED);
    when(callGuests.counsellorCall(TENANT, "co-moderator", "!room:oriso", "call-2"))
        .thenReturn(new CounsellorCall(Outcome.NOT_FOUND, null, null, null));

    assertThat(service.admitAll(coModerator, null, "!room:oriso")).containsExactly(mine);
    assertThat(other.getStatus()).isEqualTo(Status.PENDING);
  }

  @Test
  void directLinkKnocks_Should_StayWithTheConversationsCounsellor_EvenForACallMember() {
    var coModerator = consultant("co-moderator");
    when(repository.findByIdForUpdate(1L))
        .thenReturn(Optional.of(pending(1L, LocalDateTime.now())));
    inCall(coModerator, Outcome.ADMITTED);

    assertThatThrownBy(() -> service.admit(1L, coModerator)).isInstanceOf(NotFoundException.class);
    verify(callGuests, never()).counsellorCall(any(), any(), any(), any());
  }

  @Test
  void admitWaitingOnCallLink_Should_AdmitEveryPendingKnockOfTheLink() {
    var first = callLinkKnock(1L, Status.PENDING);
    var second = callLinkKnock(2L, Status.PENDING);
    when(repository.findByInviteLinkIdAndViaAndStatus(5L, Via.CALL_LINK, Status.PENDING))
        .thenReturn(List.of(first, second));

    assertThat(service.admitWaitingOnCallLink(5L, "counsellor-1")).hasSize(2);
    assertThat(first.getStatus()).isEqualTo(Status.ADMITTED);
    assertThat(second.getDecidedBy()).isEqualTo("counsellor-1");
  }

  @Test
  void cancelWaitingOnCallLink_Should_CancelEveryPendingKnockOfTheLink() {
    var waiting = callLinkKnock(1L, Status.PENDING);
    when(repository.findByInviteLinkIdAndViaAndStatus(5L, Via.CALL_LINK, Status.PENDING))
        .thenReturn(List.of(waiting));

    service.cancelWaitingOnCallLink(5L);
    assertThat(waiting.getStatus()).isEqualTo(Status.CANCELLED);
  }

  @Test
  void getOwn_Should_ReturnTheGuestsOwnCallLinkKnock() {
    var knock = callLinkKnock(1L, Status.PENDING);
    when(repository.findById(1L)).thenReturn(Optional.of(knock));

    assertThat(service.getOwn(1L, asker)).isSameAs(knock);
  }

  private GroupChatJoinRequest callLinkKnock(Long id, Status status) {
    return GroupChatJoinRequest.builder()
        .id(id)
        .via(Via.CALL_LINK)
        .inviteLinkId(5L)
        .userId("asker-1")
        .medium("VIDEO")
        .status(status)
        .requestedAt(LocalDateTime.now().minusMinutes(1))
        .build();
  }

  private static de.caritas.cob.userservice.api.model.AgencyInviteLink callLink(String owner) {
    return de.caritas.cob.userservice.api.model.AgencyInviteLink.builder()
        .id(5L)
        .linkKind("CALL")
        .status("ACTIVE")
        .consultantId(owner)
        .tenantId(TENANT)
        .callBindingId(3L)
        .build();
  }

  private static de.caritas.cob.userservice.api.model.MatrixCallBinding binding() {
    return de.caritas.cob.userservice.api.model.MatrixCallBinding.builder()
        .id(3L)
        .sourceRoomId("!room:oriso")
        .callId("call-1")
        .build();
  }

  /** The call-room membership rule of the share link (MatrixCallGuestService#counsellorCall). */
  private void inCall(Consultant who, Outcome outcome) {
    when(callGuests.counsellorCall(TENANT, who.getId(), "!room:oriso", "call-1"))
        .thenReturn(
            new CounsellorCall(
                outcome,
                outcome == Outcome.ADMITTED ? binding() : null,
                "@" + who.getId() + ":oriso",
                List.of()));
  }

  private GroupChatJoinRequest pending(Long id, LocalDateTime requestedAt) {
    return GroupChatJoinRequest.builder()
        .id(id)
        .via(Via.DIRECT_LINK)
        .sessionId(SESSION_ID)
        .userId("asker-1")
        .medium("VIDEO")
        .status(Status.PENDING)
        .requestedAt(requestedAt)
        .build();
  }

  private static Consultant consultant(String id) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(TENANT);
    return consultant;
  }
}
