package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DirectInvitationRoomMembershipTest {

  private static final String ROOM = "!room:example";

  private final MatrixSynapseService matrix = mock(MatrixSynapseService.class);
  private final DirectInvitationRoomMembership membership =
      new DirectInvitationRoomMembership(matrix);
  private Session session;
  private User guest;

  @BeforeEach
  void setUp() {
    var counsellor = new Consultant();
    counsellor.setMatrixUserId("@counsellor:example");
    session = new Session();
    session.setId(1L);
    session.setMatrixRoomId(ROOM);
    session.setConsultant(counsellor);
    guest = new User();
    guest.setMatrixUserId("@guest:example");
    when(matrix.forceJoinUserToRoom(any(), any(), any())).thenReturn(true);
    when(matrix.isRoomEncrypted(ROOM)).thenReturn(Optional.of(true));
  }

  @Test
  void appointmentRoom_Should_JoinThePerson_WithTheCounsellorAsFallback() {
    membership.addToAppointmentRoom(session, guest);

    verify(matrix).forceJoinUserToRoom(ROOM, "@guest:example", List.of("@counsellor:example"));
  }

  @Test
  void existing1to1_Should_InviteAsTheCounsellorAndJoin_WithoutTouchingRoomSettings()
      throws Exception {
    when(matrix.loginAsUserAccessToken("@counsellor:example")).thenReturn("counsellor-token");
    when(matrix.loginAsUserAccessToken("@guest:example")).thenReturn("guest-token");
    when(matrix.joinRoom(ROOM, "guest-token")).thenReturn(true);

    membership.addInvitedGuestToExistingRoom(session, guest);

    verify(matrix).inviteUserToRoom(ROOM, "@guest:example", "counsellor-token");
    verify(matrix).joinRoom(ROOM, "guest-token");
    verify(matrix, never()).getRoomHistoryVisibility(any());
    verify(matrix, never()).forceJoinUserToRoom(any(), any(), any());
    verify(matrix, never()).setUserPowerLevel(any(), any(), anyInt(), any());
  }

  @Test
  void existing1to1_Should_StillJoin_When_ThePersonIsAlreadyInvited() throws Exception {
    when(matrix.loginAsUserAccessToken("@counsellor:example")).thenReturn("counsellor-token");
    when(matrix.loginAsUserAccessToken("@guest:example")).thenReturn("guest-token");
    when(matrix.inviteUserToRoom(ROOM, "@guest:example", "counsellor-token"))
        .thenThrow(new MatrixInviteUserException("is already in the room"));
    when(matrix.joinRoom(ROOM, "guest-token")).thenReturn(true);

    membership.addInvitedGuestToExistingRoom(session, guest);

    verify(matrix).joinRoom(ROOM, "guest-token");
  }

  @Test
  void existing1to1_Should_FailWithBadGateway_When_TheJoinIsNotConfirmed() {
    when(matrix.loginAsUserAccessToken(any())).thenReturn("token");
    when(matrix.joinRoom(ROOM, "token")).thenReturn(false);

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("conversation_room_unavailable");
  }

  @Test
  void existing1to1_Should_FailWithBadGateway_When_TheCounsellorHasNoMatrixToken() {
    when(matrix.loginAsUserAccessToken("@counsellor:example")).thenReturn(null);

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("conversation_room_unavailable");
    verify(matrix, never()).joinRoom(any(), any());
  }

  @Test
  void join_Should_FailWithBadGateway_When_MatrixRefuses() {
    when(matrix.forceJoinUserToRoom(any(), any(), any())).thenReturn(false);

    assertThatThrownBy(() -> membership.addToAppointmentRoom(session, guest))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("conversation_room_unavailable");
  }

  @Test
  void remove_Should_KickAsTheCounsellor() {
    when(matrix.loginAsUserAccessToken("@counsellor:example")).thenReturn("token");
    when(matrix.kickUserFromRoom(ROOM, "@guest:example", "token")).thenReturn(true);

    assertThat(membership.remove(session, "@guest:example")).isTrue();
  }

  /** Fail closed: without a room id nobody can check the person is out, so it stays due. */
  @Test
  void remove_Should_StayDue_When_TheConversationHasNoRoomId() {
    session.setMatrixRoomId(null);

    assertThat(membership.remove(session, "@guest:example")).isFalse();
    verify(matrix, never()).kickUserFromRoom(any(), any(), any());
  }

  @Test
  void remove_Should_CountAsDone_When_TheKickFailsButThePersonHasLeft() {
    when(matrix.loginAsUserAccessToken("@counsellor:example")).thenReturn("token");
    when(matrix.kickUserFromRoom(ROOM, "@guest:example", "token")).thenReturn(false);
    when(matrix.getRoomMembership(ROOM, "@guest:example", "token"))
        .thenReturn(Optional.of("leave"));

    assertThat(membership.remove(session, "@guest:example")).isTrue();
  }

  @Test
  void remove_Should_StayDue_When_TheKickFailsAndTheMembershipIsUnknownOrJoined() {
    when(matrix.loginAsUserAccessToken("@counsellor:example")).thenReturn("token");
    when(matrix.kickUserFromRoom(ROOM, "@guest:example", "token")).thenReturn(false);
    when(matrix.getRoomMembership(ROOM, "@guest:example", "token")).thenReturn(Optional.empty());
    assertThat(membership.remove(session, "@guest:example")).isFalse();

    when(matrix.getRoomMembership(ROOM, "@guest:example", "token")).thenReturn(Optional.of("join"));
    assertThat(membership.remove(session, "@guest:example")).isFalse();
  }

  /** A refused or failed join never reached the room: the room state confirms the person is out. */
  @Test
  void remove_Should_CountAsDone_When_TheRoomStateHasNoMembershipOfThePerson() {
    when(matrix.loginAsUserAccessToken("@counsellor:example")).thenReturn("token");
    when(matrix.kickUserFromRoom(ROOM, "@guest:example", "token")).thenReturn(false);
    when(matrix.isOutOfRoom(ROOM, "@guest:example")).thenReturn(Optional.of(true));

    assertThat(membership.remove(session, "@guest:example")).isTrue();

    when(matrix.isOutOfRoom(ROOM, "@guest:example")).thenReturn(Optional.of(false));
    assertThat(membership.remove(session, "@guest:example")).isFalse();
  }

  /** "History from join" rests on Megolm: a 1:1 without encryption takes nobody by link. */
  @Test
  void existing1to1_Should_RefuseTheJoin_When_TheRoomIsNotEncrypted() {
    when(matrix.isRoomEncrypted(ROOM)).thenReturn(Optional.of(false));

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest))
        .isInstanceOf(DirectInvitationRefusedException.class)
        .extracting("reason")
        .isEqualTo(DirectInvitationRefusedException.Reason.DIRECT_INVITATION_ROOM_NOT_ENCRYPTED);
    verifyNoInvite();
  }

  @Test
  void existing1to1_Should_FailClosed_When_EncryptionCannotBeRead() {
    when(matrix.isRoomEncrypted(ROOM)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("room_encryption_unknown");
    verifyNoInvite();
  }

  /** An invite the join did not follow is withdrawn, so nobody is left invited. */
  @Test
  void existing1to1_Should_WithdrawTheInvite_When_TheJoinIsNotConfirmed() {
    when(matrix.loginAsUserAccessToken("@counsellor:example")).thenReturn("counsellor-token");
    when(matrix.loginAsUserAccessToken("@guest:example")).thenReturn("guest-token");
    when(matrix.joinRoom(ROOM, "guest-token")).thenReturn(false);

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest))
        .isInstanceOf(ResponseStatusException.class);
    verify(matrix).kickUserFromRoom(ROOM, "@guest:example", "counsellor-token");
  }

  private void verifyNoInvite() {
    try {
      verify(matrix, never()).inviteUserToRoom(any(), any(), any());
    } catch (MatrixInviteUserException impossible) {
      throw new AssertionError(impossible);
    }
    verify(matrix, never()).joinRoom(any(), any());
  }

  @Test
  void remove_Should_StayDue_When_TheCounsellorHasNoMatrixAccount() {
    session.getConsultant().setMatrixUserId(null);

    assertThat(membership.remove(session, "@guest:example")).isFalse();
  }
}
