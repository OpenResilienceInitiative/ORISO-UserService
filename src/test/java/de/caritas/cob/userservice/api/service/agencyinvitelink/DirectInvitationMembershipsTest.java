package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * ORISO-Frontend#2252: the one place that decides membership. The row lock serialises the steps;
 * these tests run the interleavings two concurrent join attempts (or a join and the sweep) can
 * produce, one step at a time.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectInvitationMembershipsTest {

  private static final long LINK_ID = 41L;
  private static final long SESSION_ID = 900L;

  @Mock private DirectInvitationMemberRepository members;
  @Mock private AgencyInviteLinkRepository links;
  @Mock private SessionAppointmentRepository appointments;
  @Mock private de.caritas.cob.userservice.api.port.out.SessionRepository sessions;

  private final AtomicReference<DirectInvitationMember> row = new AtomicReference<>();
  private DirectInvitationMemberships memberships;
  private AgencyInviteLink link;
  private Session session;
  private User user;

  @BeforeEach
  void setUp() {
    memberships =
        new DirectInvitationMemberships(members, links, appointments, sessions, Clock.systemUTC());
    when(sessions.lockById(SESSION_ID)).thenReturn(Optional.of(SESSION_ID));
    var appointment = new SessionAppointment();
    appointment.setId(UUID.randomUUID());
    appointment.setStatus(SessionAppointment.Status.SCHEDULED);
    link =
        AgencyInviteLink.builder()
            .id(LINK_ID)
            .status("ACTIVE")
            .appointmentId(appointment.getId())
            .expiresAt(now().plusDays(1))
            .build();
    session = new Session();
    session.setId(SESSION_ID);
    user = new User();
    user.setUserId("interpreter");
    when(links.findByIdForUpdate(LINK_ID)).thenAnswer(i -> Optional.of(link));
    when(appointments.findById(appointment.getId())).thenReturn(Optional.of(appointment));
    when(members.findBySessionIdAndUserIdForUpdate(SESSION_ID, "interpreter"))
        .thenAnswer(i -> Optional.ofNullable(row.get()));
    when(members.save(any()))
        .thenAnswer(
            i -> {
              DirectInvitationMember saved = i.getArgument(0);
              if (saved.getId() == null) {
                saved.setId(7L);
              }
              row.set(saved);
              return saved;
            });
  }

  /**
   * CodeRabbit on PR #1648: the conversation's lock first, the one a deletion takes before it reads
   * the members, so no reservation commits between that read and the deletion.
   */
  @Test
  void reserve_Should_LockTheConversation_BeforeItsRow() {
    memberships.reserve(session, user, link);

    var order = org.mockito.Mockito.inOrder(sessions, members);
    order.verify(sessions).lockById(SESSION_ID);
    order.verify(members).findBySessionIdAndUserIdForUpdate(SESSION_ID, "interpreter");
  }

  @Test
  void reserve_Should_LetNobodyIn_When_TheConversationWasDeletedMeanwhile() {
    when(sessions.lockById(SESSION_ID)).thenReturn(Optional.empty());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> memberships.reserve(session, user, link))
        .isInstanceOf(DirectLinkInactiveException.class);
    org.mockito.Mockito.verify(members, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void reserve_Should_NeverCountAsMember_UntilConfirmed() {
    var attempt = memberships.reserve(session, user, link);

    assertThat(attempt).isPresent();
    assertThat(DirectInvitationMemberships.isMember(row.get())).isFalse();
    assertThat(row.get().getRevokeDueAt()).as("due if the attempt is abandoned").isNotNull();

    assertThat(memberships.confirm(session, user, LINK_ID, attempt.get())).isTrue();
    assertThat(DirectInvitationMemberships.isMember(row.get())).isTrue();
    assertThat(memberships.reserve(session, user, link)).as("a member joins once").isEmpty();
  }

  /** A stale abandon (its attempt already confirmed) never touches the confirmed row. */
  @Test
  void abandon_Should_NotTouchARow_ThatItsAttemptAlreadyConfirmed() {
    var attempt = memberships.reserve(session, user, link).orElseThrow();
    memberships.confirm(session, user, LINK_ID, attempt);

    memberships.abandon(session, user, LINK_ID, attempt);

    assertThat(DirectInvitationMemberships.isMember(row.get())).isTrue();
  }

  /** Two tabs: the first attempt fails after the second took the row over. */
  @Test
  void concurrentConfirmAndAbandon_Should_LeaveOnlyTheOwningAttemptsOutcome() {
    var first = memberships.reserve(session, user, link).orElseThrow();
    var second = memberships.reserve(session, user, link).orElseThrow();

    memberships.abandon(session, user, LINK_ID, first);
    assertThat(row.get().getJoinAttempt())
        .as("the first attempt no longer owns the row")
        .isEqualTo(second);
    assertThat(memberships.confirm(session, user, LINK_ID, first)).isFalse();

    assertThat(memberships.confirm(session, user, LINK_ID, second)).isTrue();
    assertThat(DirectInvitationMemberships.isMember(row.get())).isTrue();
  }

  /** The other order: confirmed first, then the stale attempt fails and abandons. */
  @Test
  void concurrentConfirmThenStaleAbandon_Should_KeepTheConfirmedMember() {
    var first = memberships.reserve(session, user, link).orElseThrow();
    var second = memberships.reserve(session, user, link).orElseThrow();

    assertThat(memberships.confirm(session, user, LINK_ID, second)).isTrue();
    memberships.abandon(session, user, LINK_ID, first);

    assertThat(DirectInvitationMemberships.isMember(row.get())).isTrue();
    assertThat(row.get().getRemovedAt()).isNull();
  }

  /** A removal that was due before the retry stays due when the retry fails. */
  @Test
  void failedRetry_Should_NeverCancelOrPostponeAnExistingPendingRemoval() {
    var due = now().minusMinutes(10);
    row.set(
        DirectInvitationMember.builder()
            .id(7L)
            .sessionId(SESSION_ID)
            .userId("interpreter")
            .inviteLinkId(LINK_ID)
            .joinedAt(now().minusHours(1))
            .revokeDueAt(due)
            .build());

    var attempt = memberships.reserve(session, user, link).orElseThrow();
    assertThat(row.get().getRevokeDueAt()).as("reserve keeps the earlier due").isEqualTo(due);

    memberships.abandon(session, user, LINK_ID, attempt);

    assertThat(row.get().getRevokeDueAt()).isEqualTo(due);
    assertThat(row.get().getRemovedAt()).as("only the sweep marks removed").isNull();
    assertThat(row.get().getJoinAttempt()).isNull();
  }

  @Test
  void confirm_Should_LeaveTheRemovalDue_When_TheInvitationEndedDuringTheJoin() {
    var attempt = memberships.reserve(session, user, link).orElseThrow();
    link.setStatus("DISABLED");

    assertThat(memberships.confirm(session, user, LINK_ID, attempt)).isFalse();

    assertThat(DirectInvitationMemberships.isMember(row.get())).isFalse();
    assertThat(row.get().getRevokeDueAt()).isBeforeOrEqualTo(now());
    assertThat(row.get().getRemovedAt()).isNull();
  }

  /**
   * Matrix let the person in, but the sweep removed the row meanwhile: the person is in the room
   * again, so the removal is due again, never silently dropped.
   */
  @Test
  void confirm_Should_MarkTheRemovalDueAgain_When_TheSweepRemovedTheRowMeanwhile() {
    var attempt = memberships.reserve(session, user, link).orElseThrow();
    row.get().setRemovedAt(now());
    row.get().setJoinAttempt(null); // what the sweep does when Matrix confirmed

    assertThat(memberships.confirm(session, user, LINK_ID, attempt)).isFalse();

    assertThat(row.get().getRemovedAt()).isNull();
    assertThat(row.get().getRevokeDueAt()).isBeforeOrEqualTo(now());
    assertThat(DirectInvitationMemberships.isMember(row.get())).isFalse();
  }

  /** The same for a row that is gone altogether: a due row is written for the joined person. */
  @Test
  void confirm_Should_RecordADueRemoval_When_TheRowIsGone() {
    var attempt = memberships.reserve(session, user, link).orElseThrow();
    row.set(null);

    assertThat(memberships.confirm(session, user, LINK_ID, attempt)).isFalse();

    assertThat(row.get()).isNotNull();
    assertThat(row.get().getRevokeDueAt()).isBeforeOrEqualTo(now());
    assertThat(row.get().getRemovedAt()).isNull();
  }

  /** A failed join may still have reached Matrix: after a removal meanwhile it is due again. */
  @Test
  void abandon_Should_MarkTheRemovalDueAgain_When_TheSweepRemovedTheRowMeanwhile() {
    var attempt = memberships.reserve(session, user, link).orElseThrow();
    row.get().setRemovedAt(now());
    row.get().setJoinAttempt(null);

    memberships.abandon(session, user, LINK_ID, attempt);

    assertThat(row.get().getRemovedAt()).isNull();
    assertThat(row.get().getRevokeDueAt()).isBeforeOrEqualTo(now());
  }

  @Test
  void attemptRunning_Should_HoldOnlyUntilTheCap() {
    var attempt = memberships.reserve(session, user, link).orElseThrow();
    assertThat(attempt).isNotBlank();
    assertThat(DirectInvitationMemberships.attemptRunning(row.get(), now())).isTrue();

    row.get().setJoinedAt(now().minus(DirectInvitationMemberships.ATTEMPT_CAP).minusSeconds(1));
    assertThat(DirectInvitationMemberships.attemptRunning(row.get(), now()))
        .as("stale after the cap")
        .isFalse();
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }
}
