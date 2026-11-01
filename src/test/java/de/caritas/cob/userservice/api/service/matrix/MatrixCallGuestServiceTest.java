package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallGuest;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallGuestRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService.Outcome;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatrixCallGuestServiceTest {
  private static final String SOURCE = "!source:example";
  private static final String MEDIA = "!media:example";
  private static final String COUNSELLOR = "@counsellor:example";
  private static final String GUEST = "@guest:example";

  private final MatrixCallBindingRepository bindings = mock(MatrixCallBindingRepository.class);
  private final MatrixCallGuestRepository guests = mock(MatrixCallGuestRepository.class);
  private final MatrixCallConversationResolver conversations =
      mock(MatrixCallConversationResolver.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final AccountInactivityService accounts = mock(AccountInactivityService.class);
  private final List<MatrixCallGuest> saved = new ArrayList<>();

  private MatrixCallGuestService service;
  private AuthenticatedUser counsellor;
  private MatrixCallBinding binding;

  @BeforeEach
  void setUp() throws Exception {
    service =
        new MatrixCallGuestService(
            bindings, guests, conversations, users, consultants, matrix, accounts);
    counsellor = new AuthenticatedUser();
    counsellor.setUserId("counsellor");
    counsellor.setTenantId(7L);
    counsellor.setRoles(java.util.Set.of("consultant"));
    binding = binding(null);
    when(conversations.resolve(SOURCE))
        .thenReturn(Optional.of(new MatrixCallConversation(SOURCE, 7L, 45L, null)));
    when(consultants.findByIdAndDeleteDateIsNull("counsellor"))
        .thenReturn(Optional.of(consultant("counsellor", COUNSELLOR, 7L)));
    when(users.findByUserIdAndDeleteDateIsNull("guest"))
        .thenReturn(Optional.of(user("guest", GUEST, 7L)));
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(COUNSELLOR)));
    when(bindings.findBySourceRoomIdAndCallId(SOURCE, "call"))
        .thenAnswer(i -> Optional.of(binding));
    when(bindings.lockByMediaRoomId(MEDIA)).thenAnswer(i -> Optional.of(binding));
    when(matrix.loginAsUserAccessToken(COUNSELLOR)).thenReturn("counsellor-token");
    when(accounts.snapshot(anyString())).thenReturn(Optional.empty());
    lenient()
        .when(guests.saveAndFlush(any(MatrixCallGuest.class)))
        .thenAnswer(
            call -> {
              MatrixCallGuest guest = call.getArgument(0);
              saved.add(guest);
              return guest;
            });
    lenient()
        .doAnswer(call -> saved.remove((MatrixCallGuest) call.getArgument(0)))
        .when(guests)
        .delete(any(MatrixCallGuest.class));
  }

  /** The seat is written before the invite, so a failed write can never leave an unknown guest. */
  @Test
  void seatIsRecordedBeforeTheInvite() throws Exception {
    service.admit(counsellor, SOURCE, "call", "guest");

    var order = org.mockito.Mockito.inOrder(guests, matrix);
    order.verify(guests).saveAndFlush(any(MatrixCallGuest.class));
    order.verify(matrix).inviteUserToRoom(MEDIA, GUEST, "counsellor-token");
  }

  @Test
  void failedSeatWriteInvitesNobody() throws Exception {
    when(guests.saveAndFlush(any(MatrixCallGuest.class)))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("x"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.admit(counsellor, SOURCE, "call", "guest"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    verify(matrix, never()).inviteUserToRoom(anyString(), anyString(), anyString());
  }

  @Test
  void seatOfAnEndedCallIsNoLongerActive() {
    when(guests.existsByBindingIdAndMatrixUserIdAndRevokedAtIsNull(11L, GUEST)).thenReturn(true);

    assertThat(service.isActiveGuest(binding(null), GUEST)).isTrue();
    assertThat(service.isActiveGuest(binding(5000L), GUEST)).isFalse();
  }

  @Test
  void failedPowerLevelClearKeepsTheSeatOutstanding() {
    var guest = guest();
    when(guests.findByBindingIdAndRevokedAtIsNull(binding.getId())).thenReturn(List.of(guest));
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token"))
        .thenReturn(Optional.of("leave"));
    when(matrix.clearUserPowerLevel(MEDIA, GUEST, "counsellor-token")).thenReturn(false);

    service.revokeOutstanding(binding(5000L));

    assertThat(guest.getRevokedAt()).isNull();
    assertThat(guest.getRevokeAttempts()).isEqualTo(1);
  }

  @Test
  void admittingCounsellorRemovesTheGuestWhenTheCallerCannot() {
    var guest =
        MatrixCallGuest.builder()
            .id(3L)
            .bindingId(binding.getId())
            .matrixUserId(GUEST)
            .admittedByMatrixId("@cocounsellor:example")
            .admittedAt(1500L)
            .build();
    when(guests.findByBindingIdAndRevokedAtIsNull(binding.getId())).thenReturn(List.of(guest));
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token"))
        .thenReturn(Optional.of("join"));
    when(matrix.loginAsUserAccessToken("@cocounsellor:example")).thenReturn("co-token");
    when(matrix.getRoomMembership(MEDIA, GUEST, "co-token"))
        .thenReturn(Optional.of("join"), Optional.of("leave"));
    when(matrix.clearUserPowerLevel(MEDIA, GUEST, "co-token")).thenReturn(true);

    service.revokeOutstanding(binding(5000L));

    verify(matrix).removeUserFromRoom(MEDIA, GUEST, "co-token");
    assertThat(guest.getRevokedAt()).isNotNull();
  }

  @Test
  void admittedGuestIsInvitedIntoTheCallRoomOnlyAndRecorded() throws Exception {
    var result = service.admit(counsellor, SOURCE, "call", "guest");

    assertThat(result.outcome()).isEqualTo(Outcome.ADMITTED);
    assertThat(result.callRoomId()).isEqualTo(MEDIA);
    assertThat(result.guestMatrixUserId()).isEqualTo(GUEST);
    verify(matrix).inviteUserToRoom(MEDIA, GUEST, "counsellor-token");
    verify(matrix, never()).inviteUserToRoom(eq(SOURCE), anyString(), anyString());
    assertThat(saved)
        .singleElement()
        .satisfies(
            guest -> {
              assertThat(guest.getBindingId()).isEqualTo(binding.getId());
              assertThat(guest.getMatrixUserId()).isEqualTo(GUEST);
              assertThat(guest.getAdmittedByMatrixId()).isEqualTo(COUNSELLOR);
              assertThat(guest.getRevokedAt()).isNull();
            });
  }

  /** A transient membership lookup failure is "Matrix unavailable" (502), never a 500. */
  @Test
  void failedMembershipLookupIsMatrixUnavailable() {
    when(matrix.getCallRoomMembers(SOURCE))
        .thenThrow(new MatrixSynapseService.CallLookupUnavailableException("x"));

    assertThat(service.counsellorCall(7L, "counsellor", SOURCE, "call").outcome())
        .isEqualTo(Outcome.MATRIX_UNAVAILABLE);
    assertThat(service.counsellorsInConversation(7L, SOURCE)).isEmpty();
  }

  @Test
  void askerCannotAdmitGuests() throws Exception {
    var asker = new AuthenticatedUser();
    asker.setUserId("asker");
    asker.setTenantId(7L);
    asker.setRoles(java.util.Set.of("user"));

    assertThat(service.admit(asker, SOURCE, "call", "guest").outcome())
        .isEqualTo(Outcome.NOT_FOUND);
    verify(matrix, never()).inviteUserToRoom(anyString(), anyString(), anyString());
  }

  @Test
  void counsellorOutsideTheSourceConversationCannotAdmit() throws Exception {
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of("@other:example")));

    assertThat(service.admit(counsellor, SOURCE, "call", "guest").outcome())
        .isEqualTo(Outcome.NOT_FOUND);
    verify(matrix, never()).inviteUserToRoom(anyString(), anyString(), anyString());
  }

  @Test
  void endedCallAdmitsNobody() throws Exception {
    binding = binding(5000L);

    assertThat(service.admit(counsellor, SOURCE, "call", "guest").outcome())
        .isEqualTo(Outcome.NOT_FOUND);
    verify(matrix, never()).inviteUserToRoom(anyString(), anyString(), anyString());
  }

  @Test
  void counsellorsInConversationAreTheTraegersCounsellorsAmongItsMembers() throws Exception {
    when(matrix.getCallRoomMembers(SOURCE))
        .thenReturn(Optional.of(List.of(COUNSELLOR, GUEST, "@foreign:example", "@second:example")));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(COUNSELLOR))
        .thenReturn(Optional.of(consultant("counsellor", COUNSELLOR, 7L)));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull("@foreign:example"))
        .thenReturn(Optional.of(consultant("foreign", "@foreign:example", 8L)));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull("@second:example"))
        .thenReturn(Optional.of(consultant("second", "@second:example", 7L)));

    assertThat(service.counsellorsInConversation(7L, SOURCE))
        .containsExactly("counsellor", "second");
  }

  @Test
  void counsellorsInConversationIsEmptyForAnotherTraegersConversation() throws Exception {
    assertThat(service.counsellorsInConversation(8L, SOURCE)).isEmpty();
  }

  @Test
  void guestFromAnotherTenantIsRefused() throws Exception {
    when(users.findByUserIdAndDeleteDateIsNull("guest"))
        .thenReturn(Optional.of(user("guest", GUEST, 8L)));

    assertThat(service.admit(counsellor, SOURCE, "call", "guest").outcome())
        .isEqualTo(Outcome.NOT_FOUND);
    verify(matrix, never()).inviteUserToRoom(anyString(), anyString(), anyString());
  }

  @Test
  void sourceRoomMemberUsesTheNormalJoinPathAndIsNotRecordedAsGuest() throws Exception {
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(COUNSELLOR, GUEST)));

    assertThat(service.admit(counsellor, SOURCE, "call", "guest").outcome())
        .isEqualTo(Outcome.ALREADY_PARTICIPANT);
    verify(matrix, never()).inviteUserToRoom(anyString(), anyString(), anyString());
    assertThat(saved).isEmpty();
  }

  @Test
  void failedInviteWithTheGuestConfirmedAbsentRecordsNothing() throws Exception {
    failInvite();
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token"))
        .thenReturn(Optional.of("leave"));

    assertThat(service.admit(counsellor, SOURCE, "call", "guest").outcome())
        .isEqualTo(Outcome.MATRIX_UNAVAILABLE);
    assertThat(saved).as("reserved seat is withdrawn").isEmpty();
  }

  /** The invite may have landed although the call threw: the seat must stay for the cleanup. */
  @Test
  void failedInviteThatStillLeftAnInviteKeepsTheSeat() throws Exception {
    failInvite();
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token"))
        .thenReturn(Optional.of("invite"));

    assertThat(service.admit(counsellor, SOURCE, "call", "guest").outcome())
        .isEqualTo(Outcome.ADMITTED);
    assertThat(saved).singleElement();
    verify(guests, never()).delete(any(MatrixCallGuest.class));
  }

  @Test
  void failedInviteWithUnreadableMembershipKeepsTheSeat() throws Exception {
    failInvite();
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token")).thenReturn(Optional.empty());

    assertThat(service.admit(counsellor, SOURCE, "call", "guest").outcome())
        .isEqualTo(Outcome.MATRIX_UNAVAILABLE);
    assertThat(saved).as("kept for the call-end cleanup").singleElement();
    verify(guests, never()).delete(any(MatrixCallGuest.class));
  }

  private void failInvite() throws Exception {
    when(matrix.inviteUserToRoom(MEDIA, GUEST, "counsellor-token"))
        .thenThrow(
            new de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException("x"));
  }

  @Test
  void endOfCallRemovesGuestFromCallRoomAndClearsPowerLevel() {
    var guest = guest();
    when(guests.findByBindingIdAndRevokedAtIsNull(binding.getId())).thenReturn(List.of(guest));
    when(matrix.loginAsUserAccessToken(COUNSELLOR)).thenReturn("caller-token");
    when(matrix.removeUserFromRoom(MEDIA, GUEST, "caller-token")).thenReturn(true);
    when(matrix.clearUserPowerLevel(MEDIA, GUEST, "caller-token")).thenReturn(true);
    when(matrix.getRoomMembership(MEDIA, GUEST, "caller-token")).thenReturn(Optional.of("leave"));

    service.revokeOutstanding(binding(5000L));

    verify(matrix).removeUserFromRoom(MEDIA, GUEST, "caller-token");
    verify(matrix).clearUserPowerLevel(MEDIA, GUEST, "caller-token");
    verify(matrix, never()).removeUserFromRoom(eq(SOURCE), anyString(), anyString());
    assertThat(guest.getRevokedAt()).isNotNull();
  }

  @Test
  void failedRemovalStaysOutstandingForTheNextRoomEvent() {
    var guest = guest();
    when(guests.findByBindingIdAndRevokedAtIsNull(binding.getId())).thenReturn(List.of(guest));
    when(matrix.removeUserFromRoom(MEDIA, GUEST, "counsellor-token")).thenReturn(false);
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token"))
        .thenReturn(Optional.of("join"));

    service.revokeOutstanding(binding(5000L));

    assertThat(guest.getRevokedAt()).isNull();
  }

  /** Security finding 2: a pending invite is not in the joined list, yet the guest is not gone. */
  @Test
  void pendingInviteCountsAsGoneOnlyOnceItsMembershipIsLeave() {
    var guest = guest();
    when(guests.findByBindingIdAndRevokedAtIsNull(binding.getId())).thenReturn(List.of(guest));
    when(matrix.removeUserFromRoom(MEDIA, GUEST, "counsellor-token")).thenReturn(false);
    when(matrix.getRoomMembers(MEDIA)).thenReturn(Optional.of(List.of(COUNSELLOR)));
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token"))
        .thenReturn(Optional.of("invite"));
    when(matrix.kickUserFromRoom(MEDIA, GUEST, "counsellor-token")).thenReturn(false);

    service.revokeOutstanding(binding(5000L));

    verify(matrix).kickUserFromRoom(MEDIA, GUEST, "counsellor-token");
    assertThat(guest.getRevokedAt()).as("invite still pending").isNull();

    when(matrix.kickUserFromRoom(MEDIA, GUEST, "counsellor-token")).thenReturn(true);
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token"))
        .thenReturn(Optional.of("invite"), Optional.of("leave"));
    when(matrix.clearUserPowerLevel(MEDIA, GUEST, "counsellor-token")).thenReturn(true);

    service.revokeOutstanding(binding(5000L));

    assertThat(guest.getRevokedAt()).as("invite retracted").isNotNull();
  }

  /** Security finding 3: a removal that keeps failing is counted and raised at ERROR. */
  @Test
  void repeatedRemovalFailureIsCountedAndLoggedAtErrorPastTheThreshold() {
    var guest = guest();
    when(guests.findByBindingIdAndRevokedAtIsNull(binding.getId())).thenReturn(List.of(guest));
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token"))
        .thenReturn(Optional.of("invite"));

    try (var logs =
        de.caritas.cob.userservice.testutils.LogbackCaptor.forClass(MatrixCallGuestService.class)) {
      for (int attempt = 1; attempt <= MatrixCallGuestService.REVOKE_ALERT_THRESHOLD; attempt++) {
        service.revokeOutstanding(binding(5000L));
      }
      assertThat(guest.getRevokeAttempts())
          .isEqualTo(MatrixCallGuestService.REVOKE_ALERT_THRESHOLD);
      assertThat(guest.getLastRevokeAttemptAt()).isNotNull();
      assertThat(guest.getRevokedAt()).isNull();
      assertThat(logs.messages(ch.qos.logback.classic.Level.ERROR))
          .singleElement()
          .satisfies(message -> assertThat(message).contains("CALL_GUEST_REVOCATION_STUCK"));
    }
  }

  /** A removal that just failed is not retried at every sync, only after the backoff. */
  @Test
  void recentlyFailedRemovalWaitsForTheBackoff() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "guestRevokeRetryMillis", 60_000L);
    var guest = guest();
    when(guests.findByBindingIdAndRevokedAtIsNull(binding.getId())).thenReturn(List.of(guest));
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token"))
        .thenReturn(Optional.of("invite"));

    service.revokeOutstanding(binding(5000L));
    service.revokeOutstanding(binding(5000L));

    assertThat(guest.getRevokeAttempts()).isEqualTo(1);
    verify(matrix, org.mockito.Mockito.times(1))
        .removeUserFromRoom(MEDIA, GUEST, "counsellor-token");
  }

  @Test
  void sweepSkipsRoomsWhoseGuestsFailedWithinTheBackoff() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "guestRevokeRetryMillis", 60_000L);
    var cutoff = org.mockito.ArgumentCaptor.forClass(Long.class);
    when(guests.findEndedMediaRoomsWithOutstandingGuests(cutoff.capture(), any()))
        .thenReturn(List.of(MEDIA));

    assertThat(service.endedRoomsWithOutstandingGuests()).containsExactly(MEDIA);
    assertThat(cutoff.getValue()).isLessThanOrEqualTo(System.currentTimeMillis() - 60_000L);
  }

  @Test
  void runningCallRevokesNothing() {
    service.revokeOutstanding(binding);

    verify(guests, never()).findByBindingIdAndRevokedAtIsNull(any());
    verify(matrix, never()).removeUserFromRoom(anyString(), anyString(), anyString());
  }

  private MatrixCallGuest guest() {
    return MatrixCallGuest.builder()
        .id(3L)
        .bindingId(binding.getId())
        .matrixUserId(GUEST)
        .admittedByMatrixId(COUNSELLOR)
        .admittedAt(1500L)
        .build();
  }

  private MatrixCallBinding binding(Long endedAt) {
    return MatrixCallBinding.builder()
        .id(11L)
        .sourceRoomId(SOURCE)
        .callId("call")
        .mediaRoomId(MEDIA)
        .callerMatrixId(COUNSELLOR)
        .sessionId(45L)
        .tenantId(7L)
        .invitedAt(1000L)
        .inviteExpiresAt(61000L)
        .startedAt(1200L)
        .endedAt(endedAt)
        .video(true)
        .build();
  }

  private Consultant consultant(String id, String matrixId, long tenant) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setMatrixUserId(matrixId);
    consultant.setTenantId(tenant);
    return consultant;
  }

  private User user(String id, String matrixId, long tenant) {
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@example.invalid")
        .matrixUserId(matrixId)
        .tenantId(tenant)
        .build();
  }
}
