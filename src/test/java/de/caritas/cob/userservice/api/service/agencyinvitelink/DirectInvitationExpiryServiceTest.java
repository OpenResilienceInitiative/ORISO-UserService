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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectInvitationExpiryServiceTest {

  private static final long LINK_ID = 5L;
  private static final long SESSION_ID = 77L;

  @Mock private AgencyInviteLinkRepository links;
  @Mock private SessionAppointmentRepository appointments;
  @Mock private DirectInvitationMemberRepository members;
  @Mock private UserRepository users;
  @Mock private DirectInvitationRoomMembership rooms;
  @Mock private SessionArchiveService archive;
  @Mock private de.caritas.cob.userservice.api.port.out.SessionRepository sessions;

  private DirectInvitationExpiryService service;
  private AgencyInviteLink link;
  private Session conversation;
  private DirectInvitationMember guest;
  private DirectInvitationMember account;

  @BeforeEach
  void setUp() {
    service =
        new DirectInvitationExpiryService(
            links, appointments, members, sessions, users, rooms, archive);
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
            .expiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1))
            .build();
    guest =
        DirectInvitationMember.builder()
            .id(1L)
            .inviteLinkId(LINK_ID)
            .sessionId(SESSION_ID)
            .userId("guest-1")
            .guest(true)
            .joinedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1))
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
            .joinedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1))
            .build();
    var accountUser = new User();
    accountUser.setUserId("account-1");
    accountUser.setMatrixUserId("@account-1:x");
    when(members.findByInviteLinkIdAndRemovedAtIsNull(LINK_ID)).thenReturn(List.of(guest, account));
    when(users.findById("account-1")).thenReturn(Optional.of(accountUser));
    when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(conversation));
    when(users.findById("guest-1")).thenReturn(Optional.of(guestUser));
    when(rooms.remove(any(), any())).thenReturn(true);
  }

  @Test
  void expire_Should_CloseTheLink_RemoveGuests_AndArchiveTheAppointmentConversation() {
    assertThat(service.expire(LINK_ID)).isTrue();

    assertThat(link.getStatus()).isEqualTo("EXPIRED");
    verify(rooms).remove(conversation, "@guest-1:x");
    assertThat(guest.getRemovedAt()).isNotNull();
    verify(members).save(guest);
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
    link.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));

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
    when(members.findById(1L)).thenReturn(Optional.of(guest));
    when(rooms.remove(conversation, "@guest-1:x")).thenReturn(true);

    assertThat(service.retryRemoval(1L)).isTrue();
    assertThat(guest.getRemovedAt()).isNotNull();
  }

  /** Switched off or deleted: the same removal as at expiry, whatever the link's status. */
  @Test
  void revoke_Should_RemoveGuestsAndArchive_When_ALinkIsSwitchedOffOrDeleted() {
    link.setStatus("DISABLED");

    service.revoke(link);

    verify(rooms).remove(conversation, "@guest-1:x");
    verify(archive).archiveBySystem(conversation);
  }

  @Test
  void sweep_Should_RetryEveryDueRemoval_EvenOfDeletedLinks() {
    var sweep = new DirectInvitationExpirySweep(service);
    guest.setRevokeDueAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
    when(links.findExpiredInvitationIds(any(), any(), any())).thenReturn(List.of());
    when(members.findPendingRevocationIds(any(), any())).thenReturn(List.of(1L));
    when(members.findById(1L)).thenReturn(Optional.of(guest));

    sweep.sweep();

    assertThat(guest.getRemovedAt()).isNotNull();
  }

  @Test
  void sweep_Should_ExpireEveryDueInvitation_AndGoOnAfterAFailure() {
    var sweep = new DirectInvitationExpirySweep(service);
    when(links.findExpiredInvitationIds(any(), any(), any())).thenReturn(List.of(LINK_ID, 6L));
    when(links.findByIdForUpdate(6L)).thenThrow(new IllegalStateException("boom"));

    sweep.sweep();

    assertThat(link.getStatus()).isEqualTo("EXPIRED");
  }
}
