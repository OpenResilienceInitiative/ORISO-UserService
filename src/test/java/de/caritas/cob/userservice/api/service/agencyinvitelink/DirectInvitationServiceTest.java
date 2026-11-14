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
            links, appointments, members, sessions, rooms, notifications, systemMessages, archive);
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
    when(members.save(any())).thenAnswer(i -> i.getArgument(0));
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

    var opened = service.openAppointmentConversation(link, interpreter, newSession);

    assertThat(opened.session()).isSameAs(conversation);
    assertThat(opened.created()).isTrue();
    verify(newSession, never()).get();
    verify(rooms).addToAppointmentRoom(conversation, interpreter);
    verify(rooms, never()).addInvitedGuestToExistingRoom(any(), any());
    verify(members).save(any());
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

    var opened = service.openAppointmentConversation(link, interpreter, () -> null);

    assertThat(opened.created()).isTrue();
    verify(rooms).addToAppointmentRoom(conversation, interpreter);
    assertThat(removed.getRemovedAt()).isNull();
  }

  @Test
  void invitationFromAnExisting1to1_Should_AddThePersonThroughTheExistingRoomSeam() {
    var oneToOne = session(SESSION_ID, "client");
    appointment.setSession(oneToOne);
    link.setJoinsExistingSession(true);
    var interpreter = user("interpreter", true);

    var opened = service.openAppointmentConversation(link, interpreter, () -> null);

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
    assertThat(renewed.getPreviousToken()).isEqualTo("old-token");
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
