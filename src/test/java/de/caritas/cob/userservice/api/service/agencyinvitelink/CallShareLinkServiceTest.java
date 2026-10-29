package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallDevice;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService.Admission;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService.CounsellorCall;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService.Outcome;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

/** ORISO-UserService#1487: the share link of one running call. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CallShareLinkServiceTest {

  private static final long TENANT = 7L;
  private static final String COUNSELLOR = "counsellor-1";
  private static final String SOURCE = "!source:example";
  private static final String CALL_ID = "call-1";
  private static final String MEDIA = "!media:example";

  @Mock private AgencyInviteLinkRepository links;
  @Mock private MatrixCallBindingRepository bindings;
  @Mock private MatrixCallGuestService callGuests;
  @Mock private SessionRepository sessions;
  @Mock private DirectCallKnockService knocks;
  @Mock private DirectInvitePolicy directInvitePolicy;
  @Mock private TemporaryAccountPolicy temporaryAccountPolicy;
  @Mock private TenantService tenantService;
  @Mock private de.caritas.cob.userservice.api.port.out.UserRepository users;

  private CallShareLinkService service;
  private MatrixCallBinding binding;
  private User guest;

  @BeforeEach
  void setUp() {
    service =
        new CallShareLinkService(
            links,
            bindings,
            callGuests,
            sessions,
            knocks,
            directInvitePolicy,
            temporaryAccountPolicy,
            tenantService,
            users);
    binding = runningCall();
    when(bindings.findById(binding.getId())).thenReturn(Optional.of(binding));
    when(callGuests.counsellorCall(TENANT, COUNSELLOR, SOURCE, CALL_ID))
        .thenReturn(new CounsellorCall(Outcome.ADMITTED, binding, "@c:example", List.of()));
    var session = new Session();
    session.setAgencyId(99L);
    session.setConsultingTypeId(1);
    session.setMainTopicId(11L);
    when(sessions.findById(500L)).thenReturn(Optional.of(session));
    when(directInvitePolicy.allows(eq(TENANT), any(), any())).thenReturn(true);
    when(temporaryAccountPolicy.allows(eq(TENANT), any(), any())).thenReturn(true);
    when(links.save(any())).thenAnswer(call -> call.getArgument(0));
    guest = new User();
    guest.setUserId("guest-1");
    guest.setTenantId(TENANT);
    guest.setMatrixUserId("@guest:example");
  }

  @Test
  void create_Should_OpenALinkForTheRunningCall_WithBothSettingsOnByDefault() {
    when(links.findByCallBindingId(binding.getId())).thenReturn(Optional.empty());

    var view = service.create(TENANT, COUNSELLOR, SOURCE, CALL_ID, null, null);

    assertThat(view.created()).isTrue();
    assertThat(view.temporaryAccount()).isTrue();
    assertThat(view.temporaryAccountAllowed()).isTrue();
    var link = view.link();
    assertThat(link.getLinkKind()).isEqualTo("CALL");
    assertThat(link.getChatType()).isEqualTo("VIDEO");
    assertThat(link.isKnock()).isTrue();
    assertThat(link.getCallBindingId()).isEqualTo(binding.getId());
    assertThat(link.getTenantId()).isEqualTo(TENANT);
    // Kept for the entry room's consent and registration (finding 2 on hold, #1487).
    assertThat(link.getAgencyId()).isEqualTo(99L);
    assertThat(link.getConsultingTypeId()).isEqualTo(1);
    assertThat(link.getTopicId()).isEqualTo(11L);
    assertThat(link.getConsultantId()).isEqualTo(COUNSELLOR);
    assertThat(link.getStatus()).isEqualTo("ACTIVE");
    assertThat(link.getExpiresAt()).isAfter(LocalDateTime.now());
    // 24 random bytes, URL-safe: unguessable.
    assertThat(link.getToken()).hasSize(32).matches("[A-Za-z0-9_-]+");
  }

  @Test
  void create_Should_ReturnTheExistingLinkOfTheCall_WithoutChangingIt() {
    var existing = link(false, false);
    when(links.findByCallBindingId(binding.getId())).thenReturn(Optional.of(existing));

    var view = service.create(TENANT, COUNSELLOR, SOURCE, CALL_ID, true, true);

    assertThat(view.created()).isFalse();
    assertThat(view.link()).isSameAs(existing);
    assertThat(existing.isKnock()).isFalse();
    verify(links, never()).save(any());
  }

  @Test
  void create_Should_NeverOfferTemporaryAccounts_When_PlatformOrTraegerForbidsThem() {
    when(links.findByCallBindingId(binding.getId())).thenReturn(Optional.empty());
    when(temporaryAccountPolicy.allows(eq(TENANT), any(), any())).thenReturn(false);

    var view = service.create(TENANT, COUNSELLOR, SOURCE, CALL_ID, null, true);

    assertThat(view.link().isTemporaryAccount()).isFalse();
    assertThat(view.temporaryAccount()).isFalse();
    assertThat(view.temporaryAccountAllowed()).isFalse();
  }

  @Test
  void create_Should_Return404_When_TheCounsellorIsNotInTheCall() {
    when(callGuests.counsellorCall(TENANT, "stranger", SOURCE, CALL_ID))
        .thenReturn(new CounsellorCall(Outcome.NOT_FOUND, null, null, null));

    assertThatThrownBy(() -> service.create(TENANT, "stranger", SOURCE, CALL_ID, null, null))
        .isInstanceOf(NotFoundException.class);
    verify(links, never()).save(any());
  }

  @Test
  void create_Should_Return502_When_MatrixCannotTellWhoIsInTheCall() {
    when(callGuests.counsellorCall(TENANT, COUNSELLOR, SOURCE, CALL_ID))
        .thenReturn(new CounsellorCall(Outcome.MATRIX_UNAVAILABLE, null, null, null));

    assertThatThrownBy(() -> service.create(TENANT, COUNSELLOR, SOURCE, CALL_ID, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("502");
  }

  @Test
  void create_Should_Return403_When_DirectInvitationsAreNotAllowedForTheMedium() {
    when(links.findByCallBindingId(binding.getId())).thenReturn(Optional.empty());
    when(directInvitePolicy.allows(TENANT, 99L, InviteLinkChatType.VIDEO)).thenReturn(false);

    assertThatThrownBy(() -> service.create(TENANT, COUNSELLOR, SOURCE, CALL_ID, null, null))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void create_Should_Return410_When_EveryCallMembershipHasExpired() {
    expireDevices();

    assertThatThrownBy(() -> service.create(TENANT, COUNSELLOR, SOURCE, CALL_ID, null, null))
        .isInstanceOf(CallShareLinkEndedException.class);
  }

  @Test
  void update_Should_ApplyToTheSameLink_AndLetEverybodyWaitingIn_When_KnockIsSwitchedOff() {
    var link = link(true, true);
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link));

    var view = service.update(TENANT, COUNSELLOR, 5L, false, null);

    assertThat(view.link()).isSameAs(link);
    assertThat(link.isKnock()).isFalse();
    assertThat(link.isTemporaryAccount()).isTrue();
    assertThat(link.getToken()).isEqualTo("token-5");
    verify(knocks).admitWaitingOnCallLink(5L, COUNSELLOR);
  }

  @Test
  void update_Should_CancelEarlierAdmissionsForRejoins_When_KnockIsSwitchedBackOn() {
    var link = link(false, true);
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link));

    service.update(TENANT, COUNSELLOR, 5L, true, false);

    assertThat(link.isKnock()).isTrue();
    assertThat(link.isTemporaryAccount()).isFalse();
    verify(knocks, never()).admitWaitingOnCallLink(any(), any());
    verify(knocks).cancelAdmissionsOnCallLink(5L);
    // Guests already in the call keep their seat.
    verify(callGuests, never()).revokeSeat(any(), any(), any());
  }

  @Test
  void join_Should_Return403_When_ACounsellorRemovedTheGuestFromThisCall() {
    joinable(true);
    when(callGuests.wasRemoved(binding, "@guest:example")).thenReturn(true);

    assertThatThrownBy(() -> service.join("token-5", guest)).isInstanceOf(ForbiddenException.class);
    verify(knocks, never()).knockOnCallLink(any(), any(), anyString());
    verify(callGuests, never()).admitAs(any(), any(), any(), any(), any());
  }

  @Test
  void removeGuest_Should_RevokeTheSeatAndDeclineTheGuestsKnocks() {
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link(true, true)));
    when(callGuests.revokeSeat(binding, "@guest:example", "@c:example")).thenReturn(true);
    when(users.findByMatrixUserIdAndDeleteDateIsNull("@guest:example"))
        .thenReturn(Optional.of(guest));

    service.removeGuest(TENANT, COUNSELLOR, 5L, "@guest:example");

    verify(callGuests).revokeSeat(binding, "@guest:example", "@c:example");
    verify(knocks).declineGuestOnCallLink(5L, "guest-1", COUNSELLOR);
  }

  @Test
  void removeGuest_Should_Return404_When_TheCounsellorIsNotInThatCall() {
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link(true, true)));
    when(callGuests.counsellorCall(TENANT, "other", SOURCE, CALL_ID))
        .thenReturn(new CounsellorCall(Outcome.NOT_FOUND, null, null, null));

    assertThatThrownBy(() -> service.removeGuest(TENANT, "other", 5L, "@guest:example"))
        .isInstanceOf(NotFoundException.class);
    verify(callGuests, never()).revokeSeat(any(), any(), any());
  }

  @Test
  void removeGuest_Should_Return404_ForSomebodyWhoIsNoGuest() {
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link(true, true)));

    assertThatThrownBy(() -> service.removeGuest(TENANT, COUNSELLOR, 5L, "@nobody:example"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void update_Should_ClampTemporaryAccount_When_NotAllowed() {
    var link = link(true, false);
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link));
    when(temporaryAccountPolicy.allows(eq(TENANT), any(), any())).thenReturn(false);

    var view = service.update(TENANT, COUNSELLOR, 5L, null, true);

    assertThat(link.isTemporaryAccount()).isFalse();
    assertThat(view.temporaryAccountAllowed()).isFalse();
  }

  @Test
  void update_Should_Return404_When_TheCounsellorIsNotInThatCall() {
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link(true, true)));
    when(callGuests.counsellorCall(TENANT, "other", SOURCE, CALL_ID))
        .thenReturn(new CounsellorCall(Outcome.NOT_FOUND, null, null, null));

    assertThatThrownBy(() -> service.update(TENANT, "other", 5L, false, null))
        .isInstanceOf(NotFoundException.class);
    verify(links, never()).save(any());
  }

  @Test
  void update_Should_Return404_ForALinkOfAnotherTraeger() {
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link(true, true)));

    assertThatThrownBy(() -> service.update(8L, COUNSELLOR, 5L, false, null))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void update_Should_Return404_ForAnotherKindOfLink() {
    var direct = link(true, true);
    direct.setLinkKind("COUNSELLOR");
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(direct));

    assertThatThrownBy(() -> service.update(TENANT, COUNSELLOR, 5L, false, null))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void update_Should_Return410_AfterHangUp() {
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link(true, true)));
    binding.finish(System.currentTimeMillis());

    assertThatThrownBy(() -> service.update(TENANT, COUNSELLOR, 5L, false, null))
        .isInstanceOf(CallShareLinkEndedException.class);
  }

  @Test
  void join_Should_Knock_When_KnockIsOn() {
    var link = joinable(true);
    var knock = knock(GroupChatJoinRequest.Status.PENDING);
    when(knocks.knockOnCallLink(5L, guest, "VIDEO")).thenReturn(knock);

    var join = service.join("token-5", guest);

    assertThat(join.callAdmitted()).isFalse();
    assertThat(join.admission()).isEqualTo(InviteLinkAdmission.KNOCK);
    assertThat(join.knockId()).isEqualTo(77L);
    assertThat(join.callRoomId()).as("no call data before admission").isNull();
    assertThat(join.sourceRoomId()).isNull();
    verify(callGuests, never()).admitAs(any(), any(), any(), any(), any());
    assertThat(link.isKnock()).isTrue();
  }

  @Test
  void join_Should_SeatTheGuestInTheCallRoom_OnTheLinkOwnersAuthority_When_KnockIsOff() {
    joinable(false);
    when(callGuests.admitAs(TENANT, COUNSELLOR, SOURCE, CALL_ID, "guest-1"))
        .thenReturn(new Admission(Outcome.ADMITTED, MEDIA, "@guest:example", 1L));

    var join = service.join("token-5", guest);

    assertThat(join.callAdmitted()).isTrue();
    assertThat(join.admission()).isEqualTo(InviteLinkAdmission.DIRECT);
    assertThat(join.callRoomId()).isEqualTo(MEDIA);
    assertThat(join.sourceRoomId()).isEqualTo(SOURCE);
    assertThat(join.callId()).isEqualTo(CALL_ID);
    verify(knocks, never()).knockOnCallLink(any(), any(), anyString());
  }

  @Test
  void join_Should_LetInOnce_When_TheGuestWasAdmittedBefore() {
    joinable(true);
    when(knocks.callLinkAdmission(5L, guest))
        .thenReturn(Optional.of(knock(GroupChatJoinRequest.Status.ADMITTED)));
    when(callGuests.admitAs(TENANT, COUNSELLOR, SOURCE, CALL_ID, "guest-1"))
        .thenReturn(new Admission(Outcome.ADMITTED, MEDIA, "@guest:example", 1L));

    var join = service.join("token-5", guest);

    assertThat(join.callAdmitted()).isTrue();
    assertThat(join.admission()).isEqualTo(InviteLinkAdmission.KNOCK);
    assertThat(join.knockStatus()).isEqualTo(GroupChatJoinRequest.Status.ADMITTED);
    verify(knocks, never()).knockOnCallLink(any(), any(), anyString());
  }

  @Test
  void join_Should_SeatAnAdmittedGuest_OnTheAdmittingCoModeratorsAuthority() {
    joinable(true);
    var admitted = knock(GroupChatJoinRequest.Status.ADMITTED);
    admitted.setDecidedBy("co-moderator");
    when(knocks.callLinkAdmission(5L, guest)).thenReturn(Optional.of(admitted));
    when(callGuests.admitAs(TENANT, "co-moderator", SOURCE, CALL_ID, "guest-1"))
        .thenReturn(new Admission(Outcome.ADMITTED, MEDIA, "@guest:example", 1L));

    assertThat(service.join("token-5", guest).callAdmitted()).isTrue();
    verify(callGuests, never()).admitAs(TENANT, COUNSELLOR, SOURCE, CALL_ID, "guest-1");
  }

  @Test
  void join_Should_FallBackToTheLinkOwner_When_TheAdmittingCoModeratorLeftTheCall() {
    joinable(true);
    var admitted = knock(GroupChatJoinRequest.Status.ADMITTED);
    admitted.setDecidedBy("co-moderator");
    when(knocks.callLinkAdmission(5L, guest)).thenReturn(Optional.of(admitted));
    when(callGuests.admitAs(TENANT, "co-moderator", SOURCE, CALL_ID, "guest-1"))
        .thenReturn(new Admission(Outcome.NOT_FOUND, null, null, null));
    when(callGuests.admitAs(TENANT, COUNSELLOR, SOURCE, CALL_ID, "guest-1"))
        .thenReturn(new Admission(Outcome.ADMITTED, MEDIA, "@guest:example", 1L));

    assertThat(service.join("token-5", guest).callAdmitted()).isTrue();
  }

  @Test
  void
      join_Should_SeatOnAnotherCounsellorsAuthority_When_TheLinkOwnerIsNoLongerInTheConversation() {
    joinable(false);
    when(callGuests.admitAs(TENANT, COUNSELLOR, SOURCE, CALL_ID, "guest-1"))
        .thenReturn(new Admission(Outcome.NOT_FOUND, null, null, null));
    when(callGuests.counsellorsInConversation(TENANT, SOURCE)).thenReturn(List.of("co-moderator"));
    when(callGuests.admitAs(TENANT, "co-moderator", SOURCE, CALL_ID, "guest-1"))
        .thenReturn(new Admission(Outcome.ADMITTED, MEDIA, "@guest:example", 1L));

    var join = service.join("token-5", guest);

    assertThat(join.callAdmitted()).isTrue();
    assertThat(join.admission()).isEqualTo(InviteLinkAdmission.DIRECT);
  }

  @Test
  void join_Should_Return404_When_NoCounsellorIsLeftInTheConversation() {
    joinable(false);
    when(callGuests.admitAs(TENANT, COUNSELLOR, SOURCE, CALL_ID, "guest-1"))
        .thenReturn(new Admission(Outcome.NOT_FOUND, null, null, null));
    when(callGuests.counsellorsInConversation(TENANT, SOURCE)).thenReturn(List.of());

    assertThatThrownBy(() -> service.join("token-5", guest)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void join_Should_Return403_ForAGuestOfAnotherTraeger() {
    joinable(false);
    guest.setTenantId(8L);

    assertThatThrownBy(() -> service.join("token-5", guest)).isInstanceOf(ForbiddenException.class);
    verify(callGuests, never()).admitAs(any(), any(), any(), any(), any());
  }

  @Test
  void join_Should_Return403_When_DirectInvitationsWereSwitchedOffDuringTheCall() {
    joinable(false);
    when(directInvitePolicy.allows(TENANT, 99L, InviteLinkChatType.VIDEO)).thenReturn(false);

    assertThatThrownBy(() -> service.join("token-5", guest)).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void join_Should_Return410_AfterHangUp() {
    joinable(false);
    binding.finish(System.currentTimeMillis());

    assertThatThrownBy(() -> service.join("token-5", guest))
        .isInstanceOf(CallShareLinkEndedException.class);
    verify(knocks, never()).knockOnCallLink(any(), any(), anyString());
  }

  @Test
  void join_Should_Return410_When_TheHangUpWasMissedButNoMembershipIsValid() {
    joinable(true);
    expireDevices();

    assertThatThrownBy(() -> service.join("token-5", guest))
        .isInstanceOf(CallShareLinkEndedException.class);
  }

  @Test
  void join_Should_Return410_When_TheLinkOutlivedItsBackstop() {
    var link = joinable(false);
    link.setExpiresAt(LocalDateTime.now().minusMinutes(1));

    assertThatThrownBy(() -> service.join("token-5", guest))
        .isInstanceOf(CallShareLinkEndedException.class);
  }

  @Test
  void join_Should_Return404_ForAnUnknownTokenOrAnotherKindOfLink() {
    var direct = link(false, true);
    direct.setLinkKind("COUNSELLOR");
    when(links.findByToken("direct")).thenReturn(Optional.of(direct));

    assertThatThrownBy(() -> service.join("nope", guest)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.join("direct", guest)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void join_Should_Signal409_When_TheGuestIsAlreadyInTheConversation() {
    joinable(false);
    when(callGuests.admitAs(TENANT, COUNSELLOR, SOURCE, CALL_ID, "guest-1"))
        .thenReturn(new Admission(Outcome.ALREADY_PARTICIPANT, null, null, null));

    assertThatThrownBy(() -> service.join("token-5", guest))
        .isInstanceOf(CallShareLinkService.AlreadyInConversationException.class);
  }

  @Test
  void expireIfOver_Should_CloseTheLinkAndCancelWaitingKnocks_When_TheCallEnded() {
    var link = link(true, true);
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link));
    binding.finish(System.currentTimeMillis());

    assertThat(service.expireIfOver(5L)).isTrue();

    assertThat(link.getStatus()).isEqualTo("EXPIRED");
    verify(knocks).cancelWaitingOnCallLink(5L);
  }

  @Test
  void expireIfOver_Should_CloseTheLink_When_TheHangUpEventWasMissed() {
    var link = link(true, true);
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link));
    expireDevices();

    assertThat(service.expireIfOver(5L)).isTrue();
    assertThat(link.getStatus()).isEqualTo("EXPIRED");
  }

  @Test
  void expireIfOver_Should_CloseTheLink_When_ItsCallIsGone() {
    var link = link(true, true);
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link));
    when(bindings.findById(binding.getId())).thenReturn(Optional.empty());

    assertThat(service.expireIfOver(5L)).isTrue();
  }

  @Test
  void expireIfOver_Should_KeepTheLinkOfARunningCall() {
    var link = link(true, true);
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link));

    assertThat(service.expireIfOver(5L)).isFalse();
    assertThat(link.getStatus()).isEqualTo("ACTIVE");
    verify(links, never()).save(any());
  }

  @Test
  void isLive_Should_FollowTheEndAndTheMemberships() {
    long now = System.currentTimeMillis();
    assertThat(CallShareLinkService.isLive(binding, now)).isTrue();
    var ringing = MatrixCallBinding.builder().inviteExpiresAt(now + 1000).build();
    assertThat(CallShareLinkService.isLive(ringing, now)).isTrue();
    var unanswered = MatrixCallBinding.builder().inviteExpiresAt(now - 1).build();
    assertThat(CallShareLinkService.isLive(unanswered, now)).isFalse();
    binding.finish(now);
    assertThat(CallShareLinkService.isLive(binding, now)).isFalse();
  }

  @Test
  void activeLinkIds_Should_PageByIdForTheSweep() {
    var captor = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
    when(links.findByLinkKindAndStatusAndIdGreaterThanOrderByIdAsc(
            eq("CALL"), eq("ACTIVE"), eq(40L), captor.capture()))
        .thenReturn(List.of(link(true, true)));

    assertThat(service.activeLinkIds(40L, 100)).containsExactly(5L);
    assertThat(captor.getValue().getPageSize()).isEqualTo(100);
  }

  private AgencyInviteLink joinable(boolean knock) {
    var link = link(knock, true);
    when(links.findByToken("token-5")).thenReturn(Optional.of(link));
    when(links.findByIdForUpdate(5L)).thenReturn(Optional.of(link));
    when(knocks.callLinkAdmission(5L, guest)).thenReturn(Optional.empty());
    return link;
  }

  private AgencyInviteLink link(boolean knock, boolean temporaryAccount) {
    return AgencyInviteLink.builder()
        .id(5L)
        .token("token-5")
        .tenantId(TENANT)
        .agencyId(99L)
        .linkKind("CALL")
        .chatType("VIDEO")
        .anonymity("FULL")
        .consultantId(COUNSELLOR)
        .createdByUserId(COUNSELLOR)
        .createDate(LocalDateTime.now())
        .expiresAt(LocalDateTime.now().plusHours(1))
        .status("ACTIVE")
        .knock(knock)
        .temporaryAccount(temporaryAccount)
        .callBindingId(binding.getId())
        .build();
  }

  private static GroupChatJoinRequest knock(GroupChatJoinRequest.Status status) {
    return GroupChatJoinRequest.builder()
        .id(77L)
        .via(GroupChatJoinRequest.Via.CALL_LINK)
        .status(status)
        .inviteLinkId(5L)
        .userId("guest-1")
        .medium("VIDEO")
        .requestedAt(LocalDateTime.now())
        .build();
  }

  private void expireDevices() {
    long now = System.currentTimeMillis();
    binding.getDevices().replaceAll((key, device) -> device(now - 1));
  }

  private static MatrixCallDevice device(long expiresAt) {
    return new MatrixCallDevice("@c:example", expiresAt - 1000, expiresAt, true, "$e");
  }

  private static MatrixCallBinding runningCall() {
    long now = System.currentTimeMillis();
    var call =
        MatrixCallBinding.builder()
            .id(3L)
            .sourceRoomId(SOURCE)
            .callId(CALL_ID)
            .mediaRoomId(MEDIA)
            .callerMatrixId("@c:example")
            .sessionId(500L)
            .tenantId(TENANT)
            .invitedAt(now - 1000)
            .inviteExpiresAt(now - 500)
            .startedAt(now - 900)
            .video(true)
            .build();
    call.getDevices().put("_c_device", device(now + 60000));
    return call;
  }
}
