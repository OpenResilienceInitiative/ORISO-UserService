package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectInvitationExpiryServiceTest {

  private static final long LINK_ID = 5L;
  private static final long SESSION_ID = 77L;

  /** One fixed instant: fixture, service and assertions read the same time. */
  private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 15, 10, 0);

  private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

  @Mock private AgencyInviteLinkRepository links;
  @Mock private SessionAppointmentRepository appointments;
  @Mock private DirectInvitationMemberRepository members;
  @Mock private UserRepository users;
  @Mock private DirectInvitationRoomMembership rooms;
  @Mock private SessionArchiveService archive;
  @Mock private de.caritas.cob.userservice.api.port.out.SessionRepository sessions;

  private final RecordingTransactionManager transactions = new RecordingTransactionManager();
  private DirectInvitationExpiryService service;
  private AgencyInviteLink link;
  private Session conversation;
  private DirectInvitationMember guest;
  private DirectInvitationMember account;

  @BeforeEach
  void setUp() {
    service =
        new DirectInvitationExpiryService(
            links, appointments, members, sessions, users, rooms, archive, transactions, CLOCK);
    conversation = new Session();
    conversation.setId(SESSION_ID);
    conversation.setStatus(Session.SessionStatus.IN_PROGRESS);
    var appointment = new SessionAppointment();
    appointment.setId(UUID.randomUUID());
    appointment.setSession(conversation);
    link =
        AgencyInviteLink.builder()
            .id(LINK_ID)
            .linkKind("COUNSELLOR")
            .status("ACTIVE")
            .appointmentId(appointment.getId())
            .expiresAt(NOW.minusMinutes(1))
            .build();
    guest =
        DirectInvitationMember.builder()
            .id(1L)
            .inviteLinkId(LINK_ID)
            .sessionId(SESSION_ID)
            .userId("guest-1")
            .guest(true)
            .joinedAt(NOW.minusDays(1))
            .build();
    var guestUser = new User();
    guestUser.setUserId("guest-1");
    guestUser.setMatrixUserId("@guest-1:x");
    when(links.findByIdForUpdate(LINK_ID)).thenReturn(Optional.of(link));
    when(appointments.findById(appointment.getId())).thenReturn(Optional.of(appointment));
    account =
        DirectInvitationMember.builder()
            .id(2L)
            .inviteLinkId(LINK_ID)
            .sessionId(SESSION_ID)
            .userId("account-1")
            .guest(false)
            .joinedAt(NOW.minusDays(1))
            .build();
    var accountUser = new User();
    accountUser.setUserId("account-1");
    accountUser.setMatrixUserId("@account-1:x");
    when(members.findByInviteLinkIdAndRemovedAtIsNull(LINK_ID)).thenReturn(List.of(guest, account));
    when(users.findById("account-1")).thenReturn(Optional.of(accountUser));
    when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(conversation));
    when(sessions.lockById(SESSION_ID)).thenReturn(Optional.of(SESSION_ID));
    when(members.findByIdForUpdate(1L)).thenAnswer(i -> Optional.of(guest));
    when(members.findByIdForUpdate(2L)).thenAnswer(i -> Optional.of(account));
    when(users.findById("guest-1")).thenReturn(Optional.of(guestUser));
    when(rooms.remove(any(), any())).thenReturn(true);
    when(members.save(any())).thenAnswer(i -> i.getArgument(0));
    when(members.findByIdForUpdate(1L)).thenReturn(Optional.of(guest));
    when(members.findByIdForUpdate(2L)).thenReturn(Optional.of(account));
  }

  @Test
  void expire_Should_CloseTheLink_RemoveGuests_AndArchiveTheAppointmentConversation() {
    assertThat(service.expire(LINK_ID)).isTrue();

    assertThat(link.getStatus()).isEqualTo("EXPIRED");
    verify(rooms).remove(conversation, "@guest-1:x");
    assertThat(guest.getRemovedAt()).isNotNull();
    verify(members, org.mockito.Mockito.atLeastOnce()).save(guest);
    verify(archive).archiveBySystem(conversation);
  }

  @Test
  void expire_Should_KeepAnExisting1to1Open_AndOnlyRemoveTheInvitedGuests() {
    link.setJoinsExistingSession(true);

    service.expire(LINK_ID);

    verify(rooms).remove(conversation, "@guest-1:x");
    verify(archive, never()).archiveBySystem(any());
  }

  @Test
  void expire_Should_DoNothing_BeforeTheLinkRunsOut() {
    link.setExpiresAt(NOW.plusMinutes(5));

    assertThat(service.expire(LINK_ID)).isFalse();

    assertThat(link.getStatus()).isEqualTo("ACTIVE");
    verify(rooms, never()).remove(any(), any());
    verify(archive, never()).archiveBySystem(any());
  }

  @Test
  void expire_Should_DoNothing_ForALinkAnotherRunClosedAlready() {
    link.setStatus("EXPIRED");

    assertThat(service.expire(LINK_ID)).isFalse();
    verify(archive, never()).archiveBySystem(any());
  }

  @Test
  void expire_Should_StillCloseTheLink_When_NobodyEverOpenedIt() {
    when(appointments.findById(any())).thenReturn(Optional.of(new SessionAppointment()));

    assertThat(service.expire(LINK_ID)).isTrue();
    assertThat(link.getStatus()).isEqualTo("EXPIRED");
    verify(archive, never()).archiveBySystem(any());
  }

  @Test
  void expire_Should_KeepAccountsInASharedAppointmentConversation() {
    service.expire(LINK_ID);

    verify(rooms, never()).remove(conversation, "@account-1:x");
    assertThat(account.getRemovedAt()).isNull();
  }

  /** Product rule: anyone who entered an existing 1:1 by link loses access at expiry. */
  @Test
  void expire_Should_RemoveEveryoneWhoCameInByLink_FromAnExisting1to1() {
    link.setJoinsExistingSession(true);

    service.expire(LINK_ID);

    verify(rooms).remove(conversation, "@guest-1:x");
    verify(rooms).remove(conversation, "@account-1:x");
    assertThat(account.getRemovedAt()).isNotNull();
  }

  /** Fail closed: a removal Matrix did not confirm stays due and is tried again. */
  @Test
  void expire_Should_KeepAFailedRemovalDue_AndTheRetryFinishesIt() {
    when(rooms.remove(conversation, "@guest-1:x")).thenReturn(false);

    service.expire(LINK_ID);

    assertThat(guest.getRemovedAt()).isNull();
    assertThat(guest.getRevokeDueAt()).isNotNull();
    when(members.findByIdForUpdate(1L)).thenReturn(Optional.of(guest));
    when(rooms.remove(conversation, "@guest-1:x")).thenReturn(true);

    assertThat(service.retryRemoval(1L)).isTrue();
    assertThat(guest.getRemovedAt()).isNotNull();
  }

  /**
   * A join still running may not have reached the room yet: "no membership event" would read as
   * out. The sweep leaves the row alone until the attempt's cap, then treats it as stale.
   */
  @Test
  void retryRemoval_Should_WaitForARunningJoinAttempt_AndCheckItOnceStale() {
    guest.setRevokeDueAt(NOW.minusMinutes(1));
    guest.setJoinAttempt("attempt-1");
    guest.setJoinedAt(NOW.minusMinutes(1));
    when(rooms.remove(conversation, "@guest-1:x")).thenReturn(true);

    assertThat(service.retryRemoval(1L)).isFalse();
    verify(rooms, never()).remove(any(), any());
    assertThat(guest.getRemovedAt()).isNull();
    assertThat(guest.getJoinAttempt()).isEqualTo("attempt-1");

    guest.setJoinedAt(NOW.minus(DirectInvitationMemberships.ATTEMPT_CAP).minusMinutes(1));
    assertThat(service.retryRemoval(1L)).isTrue();
    assertThat(guest.getRemovedAt()).isNotNull();
    assertThat(guest.getJoinAttempt()).as("the stale attempt lost the row").isNull();
  }

  /** Switched off or deleted: the same removal as at expiry, whatever the link's status. */
  @Test
  void revoke_Should_RemoveGuestsAndArchive_When_ALinkIsSwitchedOffOrDeleted() {
    link.setStatus("DISABLED");

    service.revoke(link);

    verify(rooms).remove(conversation, "@guest-1:x");
    verify(archive).archiveBySystem(conversation);
  }

  /** The status and the due marks commit first; Matrix is called only after the commit. */
  @Test
  void expire_Should_CallMatrixOnlyAfterTheCommit() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.expire(LINK_ID);

      assertThat(link.getStatus()).isEqualTo("EXPIRED");
      assertThat(guest.getRevokeDueAt()).isNotNull();
      verify(rooms, never()).remove(any(), any());

      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);

      verify(rooms).remove(conversation, "@guest-1:x");
      assertThat(guest.getRemovedAt()).isNotNull();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /**
   * CodeRabbit on PR #1648: deleting a conversation keeps its Matrix room, and the cascade drops
   * every member row, so nothing would take the invited people out afterwards.
   */
  @Test
  void requireInvitedOutBeforeDeletion_Should_TakeEveryInvitedPersonOutOfTheRoom() {
    when(members.findBySessionIdAndRemovedAtIsNull(SESSION_ID)).thenReturn(List.of(guest, account));
    when(rooms.remove(conversation, "@guest-1:x")).thenReturn(true);
    when(rooms.remove(conversation, "@account-1:x")).thenReturn(true);

    service.requireInvitedOutBeforeDeletion(conversation);

    verify(rooms).remove(conversation, "@guest-1:x");
    verify(rooms).remove(conversation, "@account-1:x");
    assertThat(guest.getRemovedAt()).isEqualTo(NOW);
    assertThat(account.getRemovedAt()).isEqualTo(NOW);
  }

  /**
   * CodeRabbit on PR #1648: the conversation's lock before the snapshot of its members, the same
   * lock a join reservation takes, so no late join slips in between snapshot and deletion.
   */
  @Test
  void requireInvitedOutBeforeDeletion_Should_LockTheConversation_BeforeReadingItsMembers() {
    when(members.findBySessionIdAndRemovedAtIsNull(SESSION_ID)).thenReturn(List.of(guest));
    when(rooms.remove(conversation, "@guest-1:x")).thenReturn(true);

    service.requireInvitedOutBeforeDeletion(conversation);

    var order = org.mockito.Mockito.inOrder(sessions, members);
    order.verify(sessions).lockById(SESSION_ID);
    order.verify(members).findBySessionIdAndRemovedAtIsNull(SESSION_ID);
  }

  /**
   * CodeRabbit on PR #1648: a refused deletion rolls back, but the people Matrix was asked about
   * stay due in a transaction of their own, so the sweep confirms or retries every one of them.
   */
  @Test
  void requireInvitedOutBeforeDeletion_Should_KeepTheRemovalsDue_When_TheDeletionIsRefused() {
    when(members.findBySessionIdAndRemovedAtIsNull(SESSION_ID)).thenReturn(List.of(guest, account));
    when(rooms.remove(conversation, "@guest-1:x")).thenReturn(true);
    when(rooms.remove(conversation, "@account-1:x")).thenReturn(false);
    // What the rollback restores: neither row was due before.
    when(members.findByIdForUpdate(1L))
        .thenReturn(Optional.of(DirectInvitationMember.builder().id(1L).build()));
    var restoredAccount = DirectInvitationMember.builder().id(2L).build();
    when(members.findByIdForUpdate(2L)).thenReturn(Optional.of(restoredAccount));

    org.junit.jupiter.api.Assertions.assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> transactional(service).requireInvitedOutBeforeDeletion(conversation));

    assertThat(transactions.rollbacks).isEqualTo(1);
    assertThat(transactions.commits).as("one transaction per kept removal").isEqualTo(2);
    assertThat(restoredAccount.getRevokeDueAt()).isEqualTo(NOW);
  }

  @Test
  void requireInvitedOutBeforeDeletion_Should_LeaveTheConversationsOwnAdviceSeekerIn() {
    var owner = new User();
    owner.setUserId("account-1");
    conversation.setUser(owner);
    when(members.findBySessionIdAndRemovedAtIsNull(SESSION_ID)).thenReturn(List.of(guest, account));
    when(rooms.remove(conversation, "@guest-1:x")).thenReturn(true);

    service.requireInvitedOutBeforeDeletion(conversation);

    verify(rooms, never()).remove(conversation, "@account-1:x");
  }

  /** Fail closed: the deletion waits until Matrix confirmed everybody out. */
  @Test
  void requireInvitedOutBeforeDeletion_Should_RefuseTheDeletion_When_MatrixKeepsSomeoneIn() {
    when(members.findBySessionIdAndRemovedAtIsNull(SESSION_ID)).thenReturn(List.of(guest));
    when(rooms.remove(conversation, "@guest-1:x")).thenReturn(false);

    org.junit.jupiter.api.Assertions.assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> service.requireInvitedOutBeforeDeletion(conversation));
  }

  @Test
  void requireInvitedOutBeforeDeletion_Should_RefuseTheDeletion_WhileAJoinIsRunning() {
    guest.setJoinAttempt("attempt");
    guest.setJoinedAt(NOW);
    when(members.findBySessionIdAndRemovedAtIsNull(SESSION_ID)).thenReturn(List.of(guest));

    org.junit.jupiter.api.Assertions.assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> service.requireInvitedOutBeforeDeletion(conversation));
    verify(rooms, never()).remove(any(), any());
  }

  /** A lasting Matrix failure is retried with a delay, not on every sweep. */
  @Test
  void retryRemoval_Should_PushTheNextAttempt_When_MatrixStillRefuses() {
    guest.setRevokeDueAt(NOW.minusMinutes(1));
    when(rooms.remove(conversation, "@guest-1:x")).thenReturn(false);

    assertThat(service.retryRemoval(1L)).isFalse();

    assertThat(guest.getRemovedAt()).isNull();
    assertThat(guest.getRevokeDueAt())
        .isEqualTo(NOW.plus(DirectInvitationExpiryService.RETRY_DELAY));
  }

  /**
   * Through the service's own transaction boundary ({@code @Transactional}, a real transaction
   * manager): Matrix is asked only once the expiry committed.
   */
  @Test
  void expire_Should_AskMatrixOnlyAfterItsTransactionCommitted() {
    var commitsWhenMatrixWasAsked = new java.util.ArrayList<Integer>();
    when(rooms.remove(conversation, "@guest-1:x"))
        .thenAnswer(
            i -> {
              commitsWhenMatrixWasAsked.add(transactions.commits);
              return true;
            });

    assertThat(transactional(service).expire(LINK_ID)).isTrue();

    assertThat(commitsWhenMatrixWasAsked).containsExactly(1);
    assertThat(guest.getRemovedAt()).isNotNull();
  }

  /** An expiry whose commit fails is rolled back and never takes anybody out. */
  @Test
  void expire_Should_NotAskMatrix_When_ItsCommitFails() {
    transactions.failNextCommit = true;

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactional(service).expire(LINK_ID))
        .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);

    assertThat(transactions.rollbacks).isEqualTo(1);
    verify(rooms, never()).remove(any(), any());
  }

  /** The service as Spring wires it: its {@code @Transactional} methods run in transactions. */
  private DirectInvitationExpiryService transactional(DirectInvitationExpiryService target) {
    var factory = new org.springframework.aop.framework.ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new org.springframework.transaction.interceptor.TransactionInterceptor(
            (org.springframework.transaction.TransactionManager) transactions,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
    return (DirectInvitationExpiryService) factory.getProxy();
  }

  /**
   * A real transaction manager without a database: begin, commit, rollback and the synchronizations
   * (after-commit work) behave as in production; it only counts the outcomes.
   */
  static final class RecordingTransactionManager
      extends org.springframework.transaction.support.AbstractPlatformTransactionManager {

    int commits;
    int rollbacks;
    boolean failNextCommit;

    RecordingTransactionManager() {
      setRollbackOnCommitFailure(true);
    }

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(
        Object transaction, org.springframework.transaction.TransactionDefinition definition) {}

    @Override
    protected void doCommit(
        org.springframework.transaction.support.DefaultTransactionStatus status) {
      if (failNextCommit) {
        failNextCommit = false;
        throw new org.springframework.transaction.TransactionSystemException("commit failed");
      }
      commits++;
    }

    @Override
    protected void doRollback(
        org.springframework.transaction.support.DefaultTransactionStatus status) {
      rollbacks++;
    }
  }
}
