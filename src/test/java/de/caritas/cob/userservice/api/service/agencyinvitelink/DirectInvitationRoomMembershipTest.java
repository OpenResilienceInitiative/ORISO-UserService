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

  /** No room means nobody is in it: the removal is done, not retried forever. */
  @Test
  void remove_Should_CountAsDone_When_TheConversationHasNoRoom() {
    session.setMatrixRoomId(null);

    assertThat(membership.remove(session, "@guest:example")).isTrue();
    verify(matrix, never()).kickUserFromRoom(any(), any(), any());
  }

  @Test
  void remove_Should_StayDue_When_TheCounsellorHasNoMatrixAccount() {
    session.getConsultant().setMatrixUserId(null);

    assertThat(membership.remove(session, "@guest:example")).isFalse();
  }
}
