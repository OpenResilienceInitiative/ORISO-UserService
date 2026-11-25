package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallDevice;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectLinkStaffJoinService.StaffOutcome;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService.Admission;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService.Outcome;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * ORISO-Frontend#2356: a counsellor who holds a direct invitation's link joins its call only. Same
 * Träger and another Träger, "Anklopfen" on and off, already a member, no running call, removed,
 * and a switched-off or expired link.
 */
class DirectLinkStaffJoinServiceTest {

  private static final String TOKEN = "tok";
  private static final long TENANT = 7L;
  private static final long OTHER_TENANT = 8L;
  private static final long AGENCY = 70L;
  private static final long LINK_ID = 5L;
  private static final long SESSION_ID = 45L;
  private static final String ROOM = "!conversation:example";
  private static final String CALL_ROOM = "!call:example";
  private static final String OWNER = "owner";
  private static final String OWNER_MATRIX = "@owner:example";
  private static final String CALLER = "caller";
  private static final String CALLER_MATRIX = "@caller:example";

  private final AgencyInviteLinkRepository links = mock(AgencyInviteLinkRepository.class);
  private final DirectInvitationService invitations = mock(DirectInvitationService.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final SessionSupervisorRepository supervisors = mock(SessionSupervisorRepository.class);
  private final MatrixCallBindingRepository bindings = mock(MatrixCallBindingRepository.class);
  private final MatrixCallGuestService callGuests = mock(MatrixCallGuestService.class);
  private final DirectCallKnockService knocks = mock(DirectCallKnockService.class);
  private final DirectInvitePolicy policy = mock(DirectInvitePolicy.class);
  private final TenantService tenants = mock(TenantService.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);

  private final DirectLinkStaffJoinService service =
      new DirectLinkStaffJoinService(
          links,
          invitations,
          sessions,
          supervisors,
          bindings,
          callGuests,
          knocks,
          policy,
          tenants,
          consultants,
          matrix,
          mock(PlatformTransactionManager.class));

  private AgencyInviteLink link;
  private Session session;
  private Consultant caller;
  private MatrixCallBinding call;

  @BeforeEach
  void setUp() {
    link =
        AgencyInviteLink.builder()
            .id(LINK_ID)
            .token(TOKEN)
            .tenantId(TENANT)
            .agencyId(AGENCY)
            .linkKind(InviteLinkKind.COUNSELLOR.name())
            .chatType(InviteLinkChatType.VIDEO.name())
            .consultantId(OWNER)
            .status(InviteLinkStatus.ACTIVE.name())
            .appointmentId(UUID.randomUUID())
            .knock(true)
            .build();
    var owner = consultant(OWNER, OWNER_MATRIX, TENANT);
    owner.setConsultantAgencies(
        Set.of(ConsultantAgency.builder().agencyId(AGENCY).consultant(owner).build()));
    session = new Session();
    session.setId(SESSION_ID);
    session.setTenantId(TENANT);
    session.setMatrixRoomId(ROOM);
    session.setConsultant(owner);
    caller = consultant(CALLER, CALLER_MATRIX, OTHER_TENANT);
    var appointment = new SessionAppointment();
    appointment.setSession(session);
    call = runningCall();

    when(links.findByToken(TOKEN)).thenReturn(Optional.of(link));
    when(policy.allows(TENANT, AGENCY, InviteLinkChatType.VIDEO)).thenReturn(true);
    when(consultants.findByIdAndDeleteDateIsNull(OWNER)).thenReturn(Optional.of(owner));
    when(consultants.findByIdAndDeleteDateIsNull(CALLER)).thenAnswer(i -> Optional.of(caller));
    when(invitations.appointmentOf(link)).thenReturn(Optional.of(appointment));
    lenient()
        .when(supervisors.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(anyLong(), any()))
        .thenReturn(Optional.empty());
    lenient().when(matrix.getCallRoomMembers(ROOM)).thenReturn(Optional.of(List.of(OWNER_MATRIX)));
    lenient().when(bindings.findBySessionIdIn(List.of(SESSION_ID))).thenAnswer(i -> List.of(call));
    lenient().when(bindings.lockById(1L)).thenAnswer(i -> Optional.of(call));
    lenient().when(callGuests.grantsGuestAuthority(any())).thenReturn(true);
    lenient().when(callGuests.counsellorsInCall(anyLong(), any())).thenReturn(List.of(OWNER));
    lenient()
        .when(knocks.latestStaffKnock(eq(SESSION_ID), eq(CALLER), any()))
        .thenReturn(Optional.empty());
    lenient()
        .when(knocks.openStaffKnock(any(), eq(CALLER), eq(LINK_ID), any()))
        .thenAnswer(i -> knock(91L, Status.PENDING, null));
    lenient()
        .when(knocks.admitStaffWithoutKnock(any(), eq(CALLER), eq(LINK_ID), any()))
        .thenAnswer(i -> knock(92L, Status.ADMITTED, OWNER));
    lenient()
        .when(
            callGuests.admitCounsellorAs(eq(TENANT), anyString(), eq(ROOM), eq("call"), eq(CALLER)))
        .thenReturn(new Admission(Outcome.ADMITTED, CALL_ROOM, CALLER_MATRIX, 1L));
  }

  @AfterEach
  void noTenantLeftBehind() {
    assertThat(TenantContext.getCurrentTenant()).isNull();
  }

  @Test
  void withKnockOnACounsellorOfAnotherTraegerKnocksAndIsNotSeated() {
    var join = service.join(TOKEN, CALLER, null);

    assertThat(join.outcome()).isEqualTo(StaffOutcome.WAITING);
    assertThat(join.knockId()).isEqualTo(91L);
    assertThat(join.sessionId()).isNull();
    assertThat(join.callRoomId()).isNull();
    verify(callGuests, never()).admitCounsellorAs(any(), any(), any(), any(), any());
  }

  @Test
  void anOpenKnockIsReturnedWithoutRingingAgain() {
    var waiting = knock(91L, Status.PENDING, null);
    when(knocks.latestStaffKnock(eq(SESSION_ID), eq(CALLER), any()))
        .thenReturn(Optional.of(waiting));
    when(knocks.stillWaitingStaffKnock(waiting)).thenReturn(true);

    assertThat(service.join(TOKEN, CALLER, 91L).outcome()).isEqualTo(StaffOutcome.WAITING);
    verify(knocks, never()).openStaffKnock(any(), any(), any(), any());
  }

  @Test
  void onceAdmittedACounsellorOfAnotherTraegerIsSeatedInTheCallRoomOnly() {
    when(knocks.latestStaffKnock(eq(SESSION_ID), eq(CALLER), any()))
        .thenReturn(Optional.of(knock(91L, Status.ADMITTED, OWNER)));

    var join = service.join(TOKEN, CALLER, 91L);

    assertThat(join.outcome()).isEqualTo(StaffOutcome.SEATED);
    assertThat(join.callRoomId()).isEqualTo(CALL_ROOM);
    assertThat(join.sourceRoomId()).isEqualTo(ROOM);
    assertThat(join.guestMatrixUserId()).isEqualTo(CALLER_MATRIX);
    // Never the conversation: no session id for a guest counsellor.
    assertThat(join.sessionId()).isNull();
    verify(callGuests).admitCounsellorAs(TENANT, OWNER, ROOM, "call", CALLER);
  }

  @Test
  void withKnockOffACounsellorIsSeatedAtOnce() {
    link.setKnock(false);

    var join = service.join(TOKEN, CALLER, null);

    assertThat(join.outcome()).isEqualTo(StaffOutcome.SEATED);
    verify(knocks).admitStaffWithoutKnock(session, CALLER, LINK_ID, InviteLinkChatType.VIDEO);
    verify(knocks, never()).openStaffKnock(any(), any(), any(), any());
  }

  @Test
  void aCounsellorOfTheSameTraegerKnocksAndIsSeatedLikeAnyOther() {
    caller = consultant(CALLER, CALLER_MATRIX, TENANT);
    link.setKnock(false);

    assertThat(service.join(TOKEN, CALLER, null).outcome()).isEqualTo(StaffOutcome.SEATED);
  }

  @Test
  void aDeclineHoldsForTheCallEvenWithoutTheKnockId() {
    when(knocks.latestStaffKnock(eq(SESSION_ID), eq(CALLER), any()))
        .thenReturn(Optional.of(knock(91L, Status.DECLINED, OWNER)));

    assertThat(service.join(TOKEN, CALLER, null).outcome()).isEqualTo(StaffOutcome.REFUSED);
    verify(knocks, never()).openStaffKnock(any(), any(), any(), any());
  }

  @Test
  void aDeclineHoldsForTheCallAlsoWhenKnockingIsSwitchedOffAfterwards() {
    link.setKnock(false);
    when(knocks.latestStaffKnock(eq(SESSION_ID), eq(CALLER), any()))
        .thenReturn(Optional.of(knock(91L, Status.DECLINED, OWNER)));

    assertThat(service.join(TOKEN, CALLER, null).outcome()).isEqualTo(StaffOutcome.REFUSED);
    verify(knocks, never()).admitStaffWithoutKnock(any(), any(), any(), any());
  }

  @Test
  void aKnockThePageWaitedOnThatWasWithdrawnIsRefused_ANewVisitKnocksAnew() {
    when(knocks.latestStaffKnock(eq(SESSION_ID), eq(CALLER), any()))
        .thenReturn(Optional.of(knock(91L, Status.CANCELLED, null)));

    assertThat(service.join(TOKEN, CALLER, 91L).outcome()).isEqualTo(StaffOutcome.REFUSED);
    assertThat(service.join(TOKEN, CALLER, null).outcome()).isEqualTo(StaffOutcome.WAITING);
  }

  @Test
  void theConversationsCounsellorGoesToTheConversation() {
    caller = consultant(OWNER, OWNER_MATRIX, TENANT);
    when(consultants.findByIdAndDeleteDateIsNull(OWNER)).thenAnswer(i -> Optional.of(caller));
    session.setConsultant(caller);
    caller.setConsultantAgencies(
        Set.of(ConsultantAgency.builder().agencyId(AGENCY).consultant(caller).build()));

    var join = service.join(TOKEN, OWNER, null);

    assertThat(join.outcome()).isEqualTo(StaffOutcome.MEMBER);
    assertThat(join.sessionId()).isEqualTo(SESSION_ID);
    verify(knocks, never()).openStaffKnock(any(), any(), any(), any());
  }

  @Test
  void aSupervisorOfTheSameTraegerGoesToTheConversation() {
    caller = consultant(CALLER, CALLER_MATRIX, TENANT);
    when(supervisors.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(SESSION_ID, CALLER))
        .thenReturn(Optional.of(new SessionSupervisor()));

    var join = service.join(TOKEN, CALLER, null);

    assertThat(join.outcome()).isEqualTo(StaffOutcome.MEMBER);
    assertThat(join.sessionId()).isEqualTo(SESSION_ID);
  }

  @Test
  void aRoomMemberOfAnotherTraegerNeverLearnsTheSessionId() {
    when(matrix.getCallRoomMembers(ROOM))
        .thenReturn(Optional.of(List.of(OWNER_MATRIX, CALLER_MATRIX)));

    assertThatThrownBy(() -> service.join(TOKEN, CALLER, null))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void withoutARunningCallNothingIsWritten() {
    call = MatrixCallBinding.builder().id(1L).sessionId(SESSION_ID).endedAt(1L).build();

    assertThat(service.join(TOKEN, CALLER, null).outcome())
        .isEqualTo(StaffOutcome.CALL_NOT_RUNNING);
    verify(knocks, never()).openStaffKnock(any(), any(), any(), any());
    verify(knocks, never()).admitStaffWithoutKnock(any(), any(), any(), any());
  }

  @Test
  void aCallWithoutGuestAuthorityIsNotRunningForGuests() {
    when(callGuests.grantsGuestAuthority(any())).thenReturn(false);

    assertThat(service.join(TOKEN, CALLER, null).outcome())
        .isEqualTo(StaffOutcome.CALL_NOT_RUNNING);
  }

  @Test
  void anInvitationNobodyOpenedYetHasNoCall() {
    when(invitations.appointmentOf(link)).thenReturn(Optional.of(new SessionAppointment()));

    assertThat(service.join(TOKEN, CALLER, null).outcome())
        .isEqualTo(StaffOutcome.CALL_NOT_RUNNING);
  }

  @Test
  void aLinkWithoutAppointmentHasNoSharedCall() {
    link.setAppointmentId(null);

    assertThat(service.join(TOKEN, CALLER, null).outcome()).isEqualTo(StaffOutcome.NO_SHARED_CALL);
  }

  @Test
  void noCounsellorLeftInTheCallToVouchMeansNotRunning() {
    link.setKnock(false);
    when(callGuests.admitCounsellorAs(any(), any(), any(), any(), any()))
        .thenReturn(new Admission(Outcome.NOT_FOUND, null, null, null));

    assertThat(service.join(TOKEN, CALLER, null).outcome())
        .isEqualTo(StaffOutcome.CALL_NOT_RUNNING);
  }

  @Test
  void removedFromThisCallCannotComeBack() {
    when(callGuests.wasRemoved(call, CALLER_MATRIX)).thenReturn(true);

    assertThatThrownBy(() -> service.join(TOKEN, CALLER, null))
        .isInstanceOf(ForbiddenException.class);
    verify(knocks, never()).openStaffKnock(any(), any(), any(), any());
  }

  @Test
  void aSwitchedOffLinkIsNoLongerActive() {
    link.setStatus(InviteLinkStatus.DISABLED.name());

    assertThatThrownBy(() -> service.join(TOKEN, CALLER, null))
        .isInstanceOf(DirectLinkInactiveException.class);
  }

  @Test
  void anExpiredLinkSaysExpired() {
    link.setExpiresAt(LocalDateTime.now().minusDays(1));

    assertThatThrownBy(() -> service.join(TOKEN, CALLER, null))
        .isInstanceOf(DirectLinkExpiredException.class);
  }

  @Test
  void withdrawnDirectInvitationsCloseTheLink() {
    when(policy.allows(TENANT, AGENCY, InviteLinkChatType.VIDEO)).thenReturn(false);

    assertThatThrownBy(() -> service.join(TOKEN, CALLER, null))
        .isInstanceOf(DirectLinkInactiveException.class);
  }

  @Test
  void aChatLinkIsNotACallInvitation() {
    link.setChatType(InviteLinkChatType.CHAT.name());

    assertThatThrownBy(() -> service.join(TOKEN, CALLER, null))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void matrixUnavailableIsABadGateway_NotAGuess() {
    when(matrix.getCallRoomMembers(ROOM)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.join(TOKEN, CALLER, null))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void leavingTheWaitingPageCancelsTheOpenKnocksThroughThisLink() {
    service.leave(TOKEN, CALLER);

    verify(knocks).cancelWaitingStaffKnocks(LINK_ID, CALLER);
  }

  private MatrixCallBinding runningCall() {
    var binding =
        MatrixCallBinding.builder()
            .id(1L)
            .sourceRoomId(ROOM)
            .callId("call")
            .mediaRoomId(CALL_ROOM)
            .callerMatrixId(OWNER_MATRIX)
            .sessionId(SESSION_ID)
            .tenantId(TENANT)
            .invitedAt(System.currentTimeMillis() - 60_000)
            .inviteExpiresAt(System.currentTimeMillis() + 60_000)
            .startedAt(System.currentTimeMillis() - 50_000)
            .mediaObservedAt(System.currentTimeMillis() - 50_000)
            .video(true)
            .build();
    binding
        .getDevices()
        .put(
            "owner-device", new MatrixCallDevice(OWNER_MATRIX, 1L, Long.MAX_VALUE, true, "$owner"));
    return binding;
  }

  private static GroupChatJoinRequest knock(long id, Status status, String decidedBy) {
    return GroupChatJoinRequest.builder()
        .id(id)
        .via(GroupChatJoinRequest.Via.STAFF_LINK)
        .sessionId(SESSION_ID)
        .userId(CALLER)
        .inviteLinkId(LINK_ID)
        .medium("VIDEO")
        .status(status)
        .requestedAt(LocalDateTime.now())
        .decidedBy(decidedBy)
        .build();
  }

  private static Consultant consultant(String id, String matrixId, long tenant) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setMatrixUserId(matrixId);
    consultant.setTenantId(tenant);
    return consultant;
  }
}
