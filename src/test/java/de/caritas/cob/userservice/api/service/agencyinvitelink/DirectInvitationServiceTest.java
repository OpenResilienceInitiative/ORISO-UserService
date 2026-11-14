package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectInvitationServiceTest {

  private static final String OWNER_ID = "owner-1";
  private static final long LINK_ID = 41L;
  private static final long SESSION_ID = 900L;

  @Mock private AgencyInviteLinkRepository links;
  @Mock private SessionAppointmentRepository appointments;
  @Mock private DirectInvitationMemberRepository members;
  @Mock private SessionRepository sessions;
  @Mock private DirectInvitationRoomMembership rooms;
  @Mock private EventNotificationService notifications;
  @Mock private MatrixSessionSystemMessageService systemMessages;
  @Mock private SessionArchiveService archive;

  private DirectInvitationService service;
  private SessionAppointment appointment;
  private AgencyInviteLink link;
  private Consultant owner;

  @BeforeEach
  void setUp() {
    service =
        new DirectInvitationService(
            links,
            appointments,
            members,
            sessions,
            rooms,
            notifications,
            systemMessages,
            archive,
            Clock.systemUTC(),
            new DirectInvitationMemberships(members, links, appointments, Clock.systemUTC()));
    service.appBaseUrl = "https://app.example";
    owner = new Consultant();
    owner.setId(OWNER_ID);
    appointment = new SessionAppointment();
    appointment.setId(UUID.randomUUID());
    appointment.setStatus(SessionAppointment.Status.SCHEDULED);
    appointment.setStartTime(
        LocalDateTime.now(ZoneOffset.UTC).plusDays(1).toInstant(ZoneOffset.UTC));
    appointment.setEndTime(
        LocalDateTime.now(ZoneOffset.UTC).plusDays(1).plusHours(1).toInstant(ZoneOffset.UTC));
    link =
        AgencyInviteLink.builder()
            .id(LINK_ID)
            .token("old-token")
            .linkKind("COUNSELLOR")
            .chatType("VIDEO")
            .consultantId(OWNER_ID)
            .status("ACTIVE")
            .appointmentId(appointment.getId())
            .validityAfterEndMinutes(60)
            .build();
    when(appointments.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
    when(appointments.findById(appointment.getId())).thenReturn(Optional.of(appointment));
    when(appointments.save(any()))
        .thenAnswer(
            i -> {
              SessionAppointment saved = i.getArgument(0);
              if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
              }
              return saved;
            });
    when(links.save(any())).thenAnswer(i -> i.getArgument(0));
    storeMembers();
    // The locked read sees what the plain one sees.
    when(members.findBySessionIdAndUserIdForUpdate(any(), any()))
        .thenAnswer(i -> members.findBySessionIdAndUserId(i.getArgument(0), i.getArgument(1)));
    when(links.findByIdForUpdate(LINK_ID)).thenReturn(Optional.of(link));
    when(links.findByIdAndConsultantIdAndLinkKindAndChatTypeIn(
            eq(LINK_ID), eq(OWNER_ID), eq("COUNSELLOR"), any()))
        .thenReturn(Optional.of(link));
  }

  // --- attach (create with appointment) ---

  @Test
  void attach_Should_StoreTheAppointmentWithoutConversation_AndExpireAfterEndPlusValidity() {
    var start = LocalDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
    var end = start.plusMinutes(45);
    var fresh = AgencyInviteLink.builder().linkKind("COUNSELLOR").build();

    service.attachAppointment(fresh, start, end, 120);

    var saved = ArgumentCaptor.forClass(SessionAppointment.class);
    verify(appointments).save(saved.capture());
    assertThat(saved.getValue().getSession()).isNull();
    assertThat(saved.getValue().getStatus()).isEqualTo(SessionAppointment.Status.SCHEDULED);
    assertThat(fresh.getAppointmentId()).isNotNull().isEqualTo(saved.getValue().getId());
    assertThat(fresh.getValidityAfterEndMinutes()).isEqualTo(120);
    assertThat(fresh.getExpiresAt()).isEqualTo(end.plusMinutes(120));
    assertThat(fresh.getScheduledAt()).isEqualTo(start);
  }

  @Test
  void attach_Should_DefaultToOneDayAfterTheEnd() {
    var start = LocalDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
    var fresh = AgencyInviteLink.builder().build();

    service.attachAppointment(fresh, start, start.plusHours(1), null);

    assertThat(fresh.getValidityAfterEndMinutes()).isEqualTo(1440);
    assertThat(fresh.getExpiresAt()).isEqualTo(start.plusHours(1).plusDays(1));
  }

  @Test
  void attach_Should_RefuseAnAppointmentInThePast() {
    var start = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    assertThatThrownBy(
            () ->
                service.attachAppointment(
                    AgencyInviteLink.builder().build(), start, start.plusHours(1), null))
        .isInstanceOf(BadRequestException.class);
    verify(appointments, never()).save(any());
  }

  // --- list ---

  @Test
  void appointmentsOf_Should_ReadEveryAppointmentInOneQuery() {
    var other = AgencyInviteLink.builder().id(LINK_ID + 1).appointmentId(UUID.randomUUID()).build();
    var none = AgencyInviteLink.builder().id(LINK_ID + 2).build();
    when(appointments.findAllById(any())).thenReturn(List.of(appointment));

    var found = service.appointmentsOf(List.of(link, other, none));

    assertThat(found).containsOnlyKeys(appointment.getId());
    var ids = ArgumentCaptor.forClass(Iterable.class);
    verify(appointments).findAllById(ids.capture());
    assertThat(ids.getValue())
        .containsExactlyInAnyOrder(appointment.getId(), other.getAppointmentId());
    verify(appointments, never()).findById(any());
  }

  // --- change ---

  /** Two invitations to one appointment of a 1:1: moving it moves both expiries. */
  @Test
  void change_Should_MoveTheExpiryOfEveryInvitationToTheSameAppointment() {
    var sibling =
        AgencyInviteLink.builder()
            .id(LINK_ID + 1)
            .linkKind("COUNSELLOR")
            .status("ACTIVE")
            .appointmentId(appointment.getId())
            .validityAfterEndMinutes(30)
            .build();
    when(links.findByAppointmentIdForUpdate(appointment.getId()))
        .thenReturn(List.of(link, sibling));
    var start = LocalDateTime.now(ZoneOffset.UTC).plusDays(3).withNano(0);

    service.change(link, start, start.plusHours(2), null);

    assertThat(link.getExpiresAt()).isEqualTo(start.plusHours(2).plusMinutes(60));
    assertThat(sibling.getExpiresAt()).isEqualTo(start.plusHours(2).plusMinutes(30));
    assertThat(sibling.getScheduledAt()).isEqualTo(start);
    verify(links).save(sibling);
  }

  /** A validity change belongs to one invitation; the others keep theirs. */
  @Test
  void change_Should_LeaveTheOtherInvitations_When_OnlyTheValidityChanges() {
    var sibling =
        AgencyInviteLink.builder()
            .id(LINK_ID + 1)
            .appointmentId(appointment.getId())
            .validityAfterEndMinutes(30)
            .build();
    when(links.findByAppointmentIdForUpdate(appointment.getId()))
        .thenReturn(List.of(link, sibling));

    service.change(link, null, null, 120);

    assertThat(link.getValidityAfterEndMinutes()).isEqualTo(120);
    assertThat(sibling.getExpiresAt()).isNull();
    verify(links, never()).save(sibling);
  }

  // --- open ---

  @Test
  void firstOpener_Should_CreateTheAppointmentConversation_AndBindTheAppointmentToIt() {
    var conversation = session(SESSION_ID, "first-user");
    when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(conversation));
    @SuppressWarnings("unchecked")
    Supplier<NewRegistrationResponseDto> newSession = mock(Supplier.class);
    when(newSession.get())
        .thenReturn(
            new NewRegistrationResponseDto()
                .sessionId(SESSION_ID)
                .matrixRoomId("!room:x")
                .status(HttpStatus.CREATED));

    var opened = service.openAppointmentConversation(link, user("first-user", true), newSession);

    assertThat(opened.session()).isSameAs(conversation);
    assertThat(opened.created()).isTrue();
    assertThat(appointment.getSession()).isSameAs(conversation);
    var member = ArgumentCaptor.forClass(DirectInvitationMember.class);
    verify(members).save(member.capture());
    assertThat(member.getValue().getUserId()).isEqualTo("first-user");
    assertThat(member.getValue().isGuest()).isTrue();
    assertThat(member.getValue().getInviteLinkId()).isEqualTo(LINK_ID);
    verify(rooms, never()).addToAppointmentRoom(any(), any());
  }

  @Test
  void laterOpener_Should_JoinTheSameConversation_WithoutCreatingAnother() {
    var conversation = session(SESSION_ID, "first-user");
    appointment.setSession(conversation);
    @SuppressWarnings("unchecked")
    Supplier<NewRegistrationResponseDto> newSession = mock(Supplier.class);
    var interpreter = user("interpreter", true);

    var opened =
        service.joinLater(
            link, interpreter, service.openAppointmentConversation(link, interpreter, newSession));

    assertThat(opened.session()).isSameAs(conversation);
    assertThat(opened.created()).isTrue();
    verify(newSession, never()).get();
    verify(rooms).addToAppointmentRoom(conversation, interpreter);
    verify(rooms, never()).addInvitedGuestToExistingRoom(any(), any());
    verify(members, org.mockito.Mockito.atLeastOnce()).save(any());
  }

  /** The appointment lock covers the lookup only; Matrix is called after it was released. */
  @Test
  void laterOpener_Should_NotJoinTheRoom_WhileTheAppointmentIsLocked() {
    appointment.setSession(session(SESSION_ID, "first-user"));

    var opened = service.openAppointmentConversation(link, user("interpreter", true), () -> null);

    assertThat(opened.joinPending()).isTrue();
    verify(rooms, never()).addToAppointmentRoom(any(), any());
    verify(members, never()).save(any());
  }

  /**
   * Stores saved member rows by (session, user), so a second visit reads what the first one left
   * behind and one person never sees another person's row. {@code storedMember} is the row saved
   * last.
   */
  private java.util.concurrent.atomic.AtomicReference<DirectInvitationMember> storedMember;

  private final java.util.Map<String, DirectInvitationMember> memberRows =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static String key(Object sessionId, Object userId) {
    return sessionId + "/" + userId;
  }

  private java.util.concurrent.atomic.AtomicReference<DirectInvitationMember> storeMembers() {
    if (storedMember != null) {
      return storedMember;
    }
    var stored = new java.util.concurrent.atomic.AtomicReference<DirectInvitationMember>();
    storedMember = stored;
    var ids = new java.util.concurrent.atomic.AtomicLong(99L);
    when(members.save(any()))
        .thenAnswer(
            i -> {
              DirectInvitationMember m = i.getArgument(0);
              if (m.getId() == null) {
                m.setId(ids.getAndIncrement());
              }
              memberRows.put(key(m.getSessionId(), m.getUserId()), m);
              stored.set(m);
              return m;
            });
    when(members.findBySessionIdAndUserId(any(), anyString()))
        .thenAnswer(
            i -> Optional.ofNullable(memberRows.get(key(i.getArgument(0), i.getArgument(1)))));
    return stored;
  }

  /** Each person's own row decides: a third person still joins, a repeat visitor does not. */
  @Test
  void thirdOpener_Should_JoinTheRoom_While_ARepeatVisitorDoesNot() {
    var conversation = session(SESSION_ID, "first-user");
    appointment.setSession(conversation);
    var interpreter = user("interpreter", true);
    var relative = user("relative", true);

    service.joinLater(
        link, interpreter, service.openAppointmentConversation(link, interpreter, () -> null));
    var again =
        service.joinLater(
            link, interpreter, service.openAppointmentConversation(link, interpreter, () -> null));
    var third =
        service.joinLater(
            link, relative, service.openAppointmentConversation(link, relative, () -> null));

    assertThat(again.created()).isFalse();
    assertThat(third.created()).isTrue();
    verify(rooms).addToAppointmentRoom(conversation, interpreter);
    verify(rooms).addToAppointmentRoom(conversation, relative);
  }

  /** A guest who created the conversation and was removed at expiry gets back in on renewal. */
  @Test
  void removedCreator_Should_JoinAgain_AfterARenewal() {
    var conversation = session(SESSION_ID, "first-user");
    var creator = conversation.getUser();
    creator.setTemporaryAccountSnapshot(new TemporaryAccountSnapshot());
    appointment.setSession(conversation);
    var removed = member("first-user", LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
    memberRows.put(key(SESSION_ID, "first-user"), removed);

    var opened =
        service.joinLater(
            link, creator, service.openAppointmentConversation(link, creator, () -> null));

    assertThat(opened.created()).isTrue();
    verify(rooms).addToAppointmentRoom(conversation, creator);
    assertThat(removed.getRemovedAt()).isNull();
  }

  /** The 1:1's own client has no member row and never joins its own room again. */
  @Test
  void clientOfThe1to1_Should_GetTheConversation_WithoutJoining() {
    var oneToOne = session(SESSION_ID, "client");
    appointment.setSession(oneToOne);
    link.setJoinsExistingSession(true);

    var opened = service.openAppointmentConversation(link, oneToOne.getUser(), () -> null);

    assertThat(opened.joinPending()).isFalse();
  }

  /** An expiry that committed while the first person waited for the lock creates nothing. */
  @Test
  void firstOpener_Should_CreateNothing_When_TheInvitationEndedMeanwhile() {
    var ended = AgencyInviteLink.builder().id(LINK_ID).status("EXPIRED").build();
    when(links.findByIdForUpdate(LINK_ID)).thenReturn(Optional.of(ended));
    @SuppressWarnings("unchecked")
    Supplier<NewRegistrationResponseDto> newSession = mock(Supplier.class);

    assertThatThrownBy(
            () -> service.openAppointmentConversation(link, user("first-user", true), newSession))
        .isInstanceOf(DirectLinkExpiredException.class);
    verify(newSession, never()).get();
    verify(members, never()).save(any());
  }

  @Test
  void firstOpener_Should_CreateNothing_When_TheInvitationWasSwitchedOffMeanwhile() {
    var off = AgencyInviteLink.builder().id(LINK_ID).status("DISABLED").build();
    when(links.findByIdForUpdate(LINK_ID)).thenReturn(Optional.of(off));
    @SuppressWarnings("unchecked")
    Supplier<NewRegistrationResponseDto> newSession = mock(Supplier.class);

    assertThatThrownBy(
            () -> service.openAppointmentConversation(link, user("first-user", true), newSession))
        .isInstanceOf(DirectLinkInactiveException.class);
    verify(newSession, never()).get();
  }

  /** A refused join leaves no row that later counts as "already in the conversation". */
  @Test
  void refusedJoin_Should_StayRefused_When_ThePersonTriesAgain() {
    var oneToOne = session(SESSION_ID, "client");
    appointment.setSession(oneToOne);
    link.setJoinsExistingSession(true);
    var interpreter = user("interpreter", true);
    var stored = storeMembers();
    org.mockito.Mockito.doThrow(
            new DirectInvitationRefusedException(
                DirectInvitationRefusedException.Reason.DIRECT_INVITATION_ROOM_NOT_ENCRYPTED))
        .when(rooms)
        .addInvitedGuestToExistingRoom(oneToOne, interpreter);

    for (int attempt = 0; attempt < 2; attempt++) {
      var opened = service.openAppointmentConversation(link, interpreter, () -> null);
      assertThat(opened.joinPending()).as("attempt %s still has to join", attempt).isTrue();
      assertThatThrownBy(() -> service.joinLater(link, interpreter, opened))
          .isInstanceOf(DirectInvitationRefusedException.class);
      assertThat(DirectInvitationMemberships.isMember(stored.get()))
          .as("no active row after a refusal")
          .isFalse();
      assertThat(stored.get().getRemovedAt()).as("only the sweep marks removed").isNull();
      assertThat(stored.get().getRevokeDueAt())
          .isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC));
    }
    verify(rooms, org.mockito.Mockito.times(2))
        .addInvitedGuestToExistingRoom(oneToOne, interpreter);
  }

  /** Matrix could not tell: the next visit tries the join again and then gets in. */
  @Test
  void unknownEncryption_Should_TryTheJoinAgain_OnTheNextVisit() {
    var oneToOne = session(SESSION_ID, "client");
    appointment.setSession(oneToOne);
    link.setJoinsExistingSession(true);
    var interpreter = user("interpreter", true);
    var stored = storeMembers();
    org.mockito.Mockito.doThrow(
            new org.springframework.web.server.ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "room_encryption_unknown"))
        .doNothing()
        .when(rooms)
        .addInvitedGuestToExistingRoom(oneToOne, interpreter);

    var first = service.openAppointmentConversation(link, interpreter, () -> null);
    assertThatThrownBy(() -> service.joinLater(link, interpreter, first))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(DirectInvitationMemberships.isMember(stored.get())).isFalse();

    var second = service.openAppointmentConversation(link, interpreter, () -> null);
    assertThat(second.joinPending()).isTrue();
    assertThat(service.joinLater(link, interpreter, second).created()).isTrue();
    assertThat(stored.get().getRemovedAt()).isNull();
    assertThat(stored.get().getRevokeDueAt()).isNull();
  }

  /** A join that may have reached Matrix keeps the removal due, never an active row. */
  @Test
  void failedJoin_Should_LeaveNoActiveRow_AndCountAsNotJoinedOnTheNextVisit() {
    var conversation = session(SESSION_ID, "first-user");
    appointment.setSession(conversation);
    var interpreter = user("interpreter", true);
    var stored = storeMembers();
    org.mockito.Mockito.doThrow(new IllegalStateException("matrix down"))
        .when(rooms)
        .addToAppointmentRoom(conversation, interpreter);

    var opened = service.openAppointmentConversation(link, interpreter, () -> null);
    assertThatThrownBy(() -> service.joinLater(link, interpreter, opened))
        .isInstanceOf(IllegalStateException.class);

    assertThat(stored.get().getRemovedAt()).isNull();
    assertThat(stored.get().getRevokeDueAt()).isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC));
    assertThat(service.openAppointmentConversation(link, interpreter, () -> null).joinPending())
        .isTrue();
  }

  /** The person is on record, with a removal due, before Matrix is asked to let them in. */
  @Test
  void laterOpener_Should_BeRecordedAsDue_BeforeTheJoin_AndClearedAfterIt() {
    var conversation = session(SESSION_ID, "first-user");
    appointment.setSession(conversation);
    var interpreter = user("interpreter", true);
    var dueAtJoin = new java.util.concurrent.atomic.AtomicReference<LocalDateTime>();
    var stored = storeMembers();
    org.mockito.Mockito.doAnswer(
            i -> {
              dueAtJoin.set(stored.get().getRevokeDueAt());
              return null;
            })
        .when(rooms)
        .addToAppointmentRoom(conversation, interpreter);

    service.joinLater(
        link, interpreter, service.openAppointmentConversation(link, interpreter, () -> null));

    assertThat(dueAtJoin.get()).as("due while the join runs").isNotNull();
    assertThat(stored.get().getRevokeDueAt()).as("cleared once still open").isNull();
  }

  /** Switched off while the person joined: they are taken out again by the sweep. */
  @Test
  void laterOpener_Should_BeDueForRemovalNow_When_TheInvitationEndsDuringTheJoin() {
    var conversation = session(SESSION_ID, "first-user");
    appointment.setSession(conversation);
    var interpreter = user("interpreter", true);
    var opened = service.openAppointmentConversation(link, interpreter, () -> null);
    org.mockito.Mockito.doAnswer(
            i -> {
              link.setStatus("DISABLED"); // a revoke commits between check and join
              return null;
            })
        .when(rooms)
        .addToAppointmentRoom(conversation, interpreter);
    var row = ArgumentCaptor.forClass(DirectInvitationMember.class);

    assertThatThrownBy(() -> service.joinLater(link, interpreter, opened))
        .isInstanceOf(DirectLinkInactiveException.class);
    verify(members, org.mockito.Mockito.atLeastOnce()).save(row.capture());
    var last = row.getValue();
    assertThat(last.getRemovedAt()).isNull();
    assertThat(last.getRevokeDueAt()).isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC));
  }

  @Test
  void laterOpener_Should_BeDueForRemovalNow_When_TheInvitationIsDeletedDuringTheJoin() {
    var conversation = session(SESSION_ID, "first-user");
    appointment.setSession(conversation);
    var interpreter = user("interpreter", true);
    var opened = service.openAppointmentConversation(link, interpreter, () -> null);
    when(links.findByIdForUpdate(LINK_ID)).thenReturn(Optional.empty());
    var row = ArgumentCaptor.forClass(DirectInvitationMember.class);

    assertThatThrownBy(() -> service.joinLater(link, interpreter, opened))
        .isInstanceOf(DirectLinkInactiveException.class);
    verify(members, org.mockito.Mockito.atLeastOnce()).save(row.capture());
    assertThat(row.getValue().getRevokeDueAt())
        .isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC));
  }

  @Test
  void sameOpenerAgain_Should_GetTheConversationBack_WithoutJoiningTwice() {
    var conversation = session(SESSION_ID, "first-user");
    appointment.setSession(conversation);
    when(members.findBySessionIdAndUserId(SESSION_ID, "interpreter"))
        .thenReturn(Optional.of(member("interpreter", null)));

    var opened = service.openAppointmentConversation(link, user("interpreter", true), () -> null);
    var asker = service.openAppointmentConversation(link, user("first-user", false), () -> null);

    assertThat(opened.created()).isFalse();
    assertThat(asker.created()).isFalse();
    verify(rooms, never()).addToAppointmentRoom(any(), any());
  }

  @Test
  void removedGuest_Should_JoinAgain_AfterARenewal() {
    var conversation = session(SESSION_ID, "first-user");
    appointment.setSession(conversation);
    var removed = member("interpreter", LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
    when(members.findBySessionIdAndUserId(SESSION_ID, "interpreter"))
        .thenReturn(Optional.of(removed));
    var interpreter = user("interpreter", true);

    var opened =
        service.joinLater(
            link, interpreter, service.openAppointmentConversation(link, interpreter, () -> null));

    assertThat(opened.created()).isTrue();
    verify(rooms).addToAppointmentRoom(conversation, interpreter);
    assertThat(removed.getRemovedAt()).isNull();
  }

  @Test
  void invitationFromAnExisting1to1_Should_AddThePersonAsAPlainMemberOfThe1to1() {
    var oneToOne = session(SESSION_ID, "client");
    appointment.setSession(oneToOne);
    link.setJoinsExistingSession(true);
    var interpreter = user("interpreter", true);

    var opened =
        service.joinLater(
            link, interpreter, service.openAppointmentConversation(link, interpreter, () -> null));

    assertThat(opened.created()).isTrue();
    verify(rooms).addInvitedGuestToExistingRoom(oneToOne, interpreter);
    verify(rooms, never()).addToAppointmentRoom(any(), any());
  }

  @Test
  void cancelledAppointment_Should_ReadAsNoLongerActive() {
    appointment.setStatus(SessionAppointment.Status.CANCELLED);
    assertThatThrownBy(() -> service.openAppointmentConversation(link, user("u", true), () -> null))
        .isInstanceOf(DirectLinkInactiveException.class);
  }

  // --- renew ("Neuen Link senden") ---

  @Test
  void renew_Should_IssueANewToken_KeepTheAppointment_AndPostTheLinkIntoTheConversation() {
    var conversation = session(SESSION_ID, "first-user");
    conversation.setStatus(SessionStatus.IN_ARCHIVE);
    appointment.setSession(conversation);
    link.setStatus("EXPIRED");
    var appointmentId = link.getAppointmentId();
    when(systemMessages.postSystemMessage(any(), anyString(), eq(true))).thenReturn(true);

    var renewed = service.renew(owner, LINK_ID, null);

    assertThat(renewed.getToken()).isNotEqualTo("old-token").hasSizeGreaterThan(20);
    assertThat(renewed.getReplacedTokens()).containsExactly("old-token");
    assertThat(renewed.getAppointmentId()).isEqualTo(appointmentId);
    assertThat(renewed.getStatus()).isEqualTo("ACTIVE");
    assertThat(renewed.getExpiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
    verify(archive).reopenBySystem(conversation);
    var body = ArgumentCaptor.forClass(String.class);
    verify(systemMessages).postSystemMessage(eq(conversation), body.capture(), eq(true));
    assertThat(body.getValue())
        .startsWith("[SYSTEM_NOTIFICATION]")
        .contains("DIRECT_INVITATION_RENEWED")
        .contains("https://app.example/invite/" + renewed.getToken());
  }

  @Test
  void renew_Should_CountTheValidityFromNow_When_TheAppointmentIsOver() {
    appointment.setStartTime(
        LocalDateTime.now(ZoneOffset.UTC).minusHours(5).toInstant(ZoneOffset.UTC));
    appointment.setEndTime(
        LocalDateTime.now(ZoneOffset.UTC).minusHours(4).toInstant(ZoneOffset.UTC));

    var renewed = service.renew(owner, LINK_ID, 90);

    assertThat(renewed.getValidityAfterEndMinutes()).isEqualTo(90);
    assertThat(renewed.getExpiresAt())
        .isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(85))
        .isBefore(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(95));
  }

  @Test
  void renew_Should_Conflict_When_TheLinkHasNoAppointment() {
    link.setAppointmentId(null);
    assertThatThrownBy(() -> service.renew(owner, LINK_ID, null))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void renew_Should_Conflict_When_TheNewLinkWouldAlreadyBeExpired() {
    appointment.setEndTime(
        LocalDateTime.now(ZoneOffset.UTC).minusHours(1).toInstant(ZoneOffset.UTC));
    appointment.setStartTime(
        LocalDateTime.now(ZoneOffset.UTC).minusHours(2).toInstant(ZoneOffset.UTC));
    assertThatThrownBy(() -> service.renew(owner, LINK_ID, 0))
        .isInstanceOf(ConflictException.class);
  }

  /** Every renewal keeps the tokens before it, so the oldest link still reads as expired. */
  @Test
  void renew_Should_KeepEveryReplacedToken_AcrossSeveralRenewals() {
    var first = service.renew(owner, LINK_ID, null).getToken();
    var renewed = service.renew(owner, LINK_ID, null);

    assertThat(renewed.getReplacedTokens()).containsExactlyInAnyOrder("old-token", first);
  }

  /** Two renewals at once: the second works on the row the first wrote, under its lock. */
  @Test
  void renew_Should_WorkOnTheLockedRow() {
    var current =
        AgencyInviteLink.builder()
            .id(LINK_ID)
            .token("token-of-a-concurrent-renewal")
            .linkKind("COUNSELLOR")
            .chatType("VIDEO")
            .consultantId(OWNER_ID)
            .status("ACTIVE")
            .appointmentId(appointment.getId())
            .validityAfterEndMinutes(60)
            .build();
    when(links.findByIdForUpdate(LINK_ID)).thenReturn(Optional.of(current));

    var renewed = service.renew(owner, LINK_ID, null);

    assertThat(renewed).isSameAs(current);
    assertThat(renewed.getReplacedTokens()).containsExactly("token-of-a-concurrent-renewal");
  }

  @Test
  void renew_Should_NotFindAnotherCounsellorsLink() {
    var other = new Consultant();
    other.setId("other");
    assertThatThrownBy(() -> service.renew(other, LINK_ID, null))
        .isInstanceOf(NotFoundException.class);
  }

  // --- request renewal ("Neuen Link anfragen") ---

  @Test
  void requestRenewal_Should_TellTheCounsellor_When_TheCallerIsInTheConversation() {
    var conversation = session(SESSION_ID, "first-user");
    appointment.setSession(conversation);
    when(links.findByToken("old-token")).thenReturn(Optional.empty());
    when(links.findFirstByPreviousToken("old-token")).thenReturn(Optional.of(link));
    when(members.findBySessionIdAndUserId(SESSION_ID, "interpreter"))
        .thenReturn(Optional.of(member("interpreter", LocalDateTime.now(ZoneOffset.UTC))));

    service.requestRenewal("old-token", user("interpreter", true));

    verify(notifications)
        .createDirectInvitationRenewalRequestedNotification(
            conversation, LINK_ID, "interpreter", "old-token");
  }

  /** A notice that was not stored must not read as "Anfrage gesendet". */
  @Test
  void requestRenewal_Should_AnswerServiceUnavailable_When_TheNoticeIsNotStored() {
    appointment.setSession(session(SESSION_ID, "first-user"));
    when(links.findByToken("old-token")).thenReturn(Optional.of(link));
    when(members.findBySessionIdAndUserId(SESSION_ID, "interpreter"))
        .thenReturn(Optional.of(member("interpreter", null)));
    org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
        .when(notifications)
        .createDirectInvitationRenewalRequestedNotification(any(), any(), any(), any());

    assertThatThrownBy(() -> service.requestRenewal("old-token", user("interpreter", true)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .extracting("statusCode")
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void requestRenewal_Should_AnswerNotFound_ForSomebodyOutsideTheConversation() {
    appointment.setSession(session(SESSION_ID, "first-user"));
    when(links.findByToken("old-token")).thenReturn(Optional.of(link));

    assertThatThrownBy(() -> service.requestRenewal("old-token", user("stranger", false)))
        .isInstanceOf(NotFoundException.class);
    verify(notifications, never())
        .createDirectInvitationRenewalRequestedNotification(any(), any(), any(), any());
  }

  @Test
  void requestRenewal_Should_Conflict_ForALinkWithoutAppointment() {
    link.setAppointmentId(null);
    when(links.findByToken("old-token")).thenReturn(Optional.of(link));

    assertThatThrownBy(() -> service.requestRenewal("old-token", user("u", false)))
        .isInstanceOf(ConflictException.class);
  }

  // --- helpers ---

  private Session session(long id, String userId) {
    var session = new Session();
    session.setId(id);
    session.setMatrixRoomId("!room:x");
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setConsultant(owner);
    session.setUser(user(userId, false));
    return session;
  }

  private static User user(String userId, boolean temporary) {
    var user = new User();
    user.setUserId(userId);
    user.setMatrixUserId("@" + userId + ":x");
    if (temporary) {
      user.setTemporaryAccountSnapshot(new TemporaryAccountSnapshot());
    }
    return user;
  }

  private static DirectInvitationMember member(String userId, LocalDateTime removedAt) {
    return DirectInvitationMember.builder()
        .sessionId(SESSION_ID)
        .userId(userId)
        .inviteLinkId(LINK_ID)
        .guest(true)
        .joinedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1))
        .removedAt(removedAt)
        .build();
  }

  @SuppressWarnings("unused")
  private static List<DirectInvitationMember> none() {
    return List.of();
  }
}
