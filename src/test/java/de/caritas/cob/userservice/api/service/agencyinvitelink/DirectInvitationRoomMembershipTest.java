package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
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
  }

  @Test
  void appointmentRoom_Should_JoinThePerson_WithTheCounsellorAsFallback() {
    membership.addToAppointmentRoom(session, guest);

    verify(matrix).forceJoinUserToRoom(ROOM, "@guest:example", List.of("@counsellor:example"));
  }

  @Test
  void existing1to1_Should_RefuseTheJoin_While_TheRoomShowsEarlierMessages() {
    when(matrix.getRoomHistoryVisibility(ROOM)).thenReturn(Optional.of("shared"));

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest))
        .isInstanceOf(DirectInvitationRefusedException.class)
        .extracting("reason")
        .isEqualTo(DirectInvitationRefusedException.Reason.DIRECT_INVITATION_HISTORY_SHARED);
    verify(matrix, never()).forceJoinUserToRoom(any(), any(), any());
  }

  @Test
  void existing1to1_Should_RefuseTheJoin_When_TheHistorySettingIsUnknown() {
    when(matrix.getRoomHistoryVisibility(ROOM)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> membership.addInvitedGuestToExistingRoom(session, guest))
        .isInstanceOf(ResponseStatusException.class);
    verify(matrix, never()).forceJoinUserToRoom(any(), any(), any());
  }

  @Test
  void existing1to1_Should_JoinThePerson_When_TheRoomHidesEarlierMessages() {
    when(matrix.getRoomHistoryVisibility(ROOM)).thenReturn(Optional.of("joined"));

    membership.addInvitedGuestToExistingRoom(session, guest);

    verify(matrix).forceJoinUserToRoom(ROOM, "@guest:example", List.of("@counsellor:example"));
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
