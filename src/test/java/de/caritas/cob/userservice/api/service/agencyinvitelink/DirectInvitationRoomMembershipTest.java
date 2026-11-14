package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import java.util.List;
import java.util.Map;
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
  }

  @Test
  void appointmentRoom_Should_JoinThePerson_WithTheCounsellorAsFallback() {
    membership.addToAppointmentRoom(session, guest);

    verify(matrix).forceJoinUserToRoom(ROOM, "@guest:example", List.of("@counsellor:example"));
  }

  private void matrixAccepts() {
    when(matrix.prepareAdminAsRoomOwner(any(), any())).thenReturn(true);
    when(matrix.lockRoomStateEvents(any(), any(), anyInt())).thenReturn(true);
    when(matrix.putRoomStateAsAdmin(any(), any(), any(), any())).thenReturn(true);
  }

  @Test
  void existing1to1_Should_LockHideAndMark_BeforeTheJoin() {
    matrixAccepts();
    when(matrix.getRoomHistoryVisibility(ROOM))
        .thenReturn(Optional.of("shared"), Optional.of("joined"));

    membership.addInvitedGuestToExistingRoom(session, guest, 7L);

    var order = inOrder(matrix);
    order.verify(matrix).prepareAdminAsRoomOwner(ROOM, "@counsellor:example");
    order
        .verify(matrix)
        .lockRoomStateEvents(
            ROOM,
            Map.of(
                "m.room.history_visibility",
                100,
                DirectInvitationRoomMembership.LINK_INVITED_MEMBER_EVENT,
                100),
            50);
    order
        .verify(matrix)
        .putRoomStateAsAdmin(
            ROOM, "m.room.history_visibility", "", Map.of("history_visibility", "joined"));
    order
        .verify(matrix)
        .putRoomStateAsAdmin(
            eq(ROOM),
            eq(DirectInvitationRoomMembership.LINK_INVITED_MEMBER_EVENT),
            eq("@guest:example"),
            argThat(content -> Long.valueOf(7L).equals(content.get("link_id"))));
    order
        .verify(matrix)
        .forceJoinUserToRoom(ROOM, "@guest:example", List.of("@counsellor:example"));
  }

  @Test
  void existing1to1_Should_NotRewriteTheHistorySetting_When_ItIsJoinedAlready() {
    matrixAccepts();
    when(matrix.getRoomHistoryVisibility(ROOM)).thenReturn(Optional.of("joined"));

    membership.addInvitedGuestToExistingRoom(session, guest, 7L);

    verify(matrix, never())
        .putRoomStateAsAdmin(any(), eq("m.room.history_visibility"), any(), any());
    verify(matrix).forceJoinUserToRoom(ROOM, "@guest:example", List.of("@counsellor:example"));
  }

  @Test
  void existing1to1_Should_RefuseTheJoin_When_TheBackendCannotTakeOverTheRoom() {
    matrixAccepts();
    when(matrix.prepareAdminAsRoomOwner(any(), any())).thenReturn(false);

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest, 7L))
        .isInstanceOf(ResponseStatusException.class);
    verify(matrix, never()).putRoomStateAsAdmin(any(), any(), any(), any());
    verify(matrix, never()).forceJoinUserToRoom(any(), any(), any());
  }

  @Test
  void existing1to1_Should_RefuseTheJoin_When_MembersCouldStillChangeTheMarksOrTheHistory() {
    matrixAccepts();
    when(matrix.lockRoomStateEvents(any(), any(), anyInt())).thenReturn(false);

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest, 7L))
        .isInstanceOf(ResponseStatusException.class);
    verify(matrix, never()).putRoomStateAsAdmin(any(), any(), any(), any());
    verify(matrix, never()).forceJoinUserToRoom(any(), any(), any());
  }

  @Test
  void existing1to1_Should_RefuseTheJoin_When_TheHistoryCannotBeHidden() {
    matrixAccepts();
    when(matrix.getRoomHistoryVisibility(ROOM)).thenReturn(Optional.of("shared"));
    when(matrix.putRoomStateAsAdmin(any(), eq("m.room.history_visibility"), any(), any()))
        .thenReturn(false);

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest, 7L))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("room_history_not_hidden");
    verify(matrix, never()).forceJoinUserToRoom(any(), any(), any());
  }

  @Test
  void existing1to1_Should_RefuseTheJoin_When_MatrixStillShowsEarlierMessagesAfterTheWrite() {
    matrixAccepts();
    when(matrix.getRoomHistoryVisibility(ROOM)).thenReturn(Optional.of("shared"));

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest, 7L))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("room_history_not_hidden");
    verify(matrix, never()).forceJoinUserToRoom(any(), any(), any());
  }

  @Test
  void existing1to1_Should_RefuseTheJoin_When_TheHistorySettingIsUnknown() {
    matrixAccepts();
    when(matrix.getRoomHistoryVisibility(ROOM)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest, 7L))
        .isInstanceOf(ResponseStatusException.class);
    verify(matrix, never()).putRoomStateAsAdmin(any(), any(), any(), any());
    verify(matrix, never()).forceJoinUserToRoom(any(), any(), any());
  }

  @Test
  void existing1to1_Should_RefuseTheJoin_When_ThePersonCannotBeMarked() {
    matrixAccepts();
    when(matrix.getRoomHistoryVisibility(ROOM)).thenReturn(Optional.of("joined"));
    when(matrix.putRoomStateAsAdmin(
            any(), eq(DirectInvitationRoomMembership.LINK_INVITED_MEMBER_EVENT), any(), any()))
        .thenReturn(false);

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest, 7L))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("conversation_room_unavailable");
    verify(matrix, never()).forceJoinUserToRoom(any(), any(), any());
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
}
