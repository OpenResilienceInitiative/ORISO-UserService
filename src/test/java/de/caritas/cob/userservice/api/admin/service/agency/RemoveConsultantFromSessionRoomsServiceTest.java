package de.caritas.cob.userservice.api.admin.service.agency;

import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RemoveConsultantFromSessionRoomsServiceTest {

  private static final String MATRIX_ROOM_ID = "!room:matrix.oriso.org";
  private static final String ASSIGNED_MATRIX_ID = "@assigned:matrix.oriso.org";
  private static final String SURPLUS_MATRIX_ID = "@surplus:matrix.oriso.org";

  @InjectMocks
  private RemoveConsultantFromSessionRoomsService removeConsultantFromSessionRoomsService;

  @Mock private GroupChatMembershipService groupChatMembershipService;

  private Session sessionWithAssignedConsultant(String assignedConsultantId) {
    var assigned = new Consultant();
    assigned.setId(assignedConsultantId);
    var session = new Session();
    session.setId(1L);
    session.setConsultant(assigned);
    session.setMatrixRoomId(MATRIX_ROOM_ID);
    return session;
  }

  private ResolvedRoomMember consultantMember(String accountId, String matrixUserId) {
    return new ResolvedRoomMember(matrixUserId, accountId, accountId, accountId, true);
  }

  private ResolvedRoomMember askerMember(String accountId, String matrixUserId) {
    return new ResolvedRoomMember(matrixUserId, accountId, accountId, accountId, false);
  }

  @Test
  void removeConsultantFromSessions_Should_RemoveSurplusConsultant_ButNotAssignedOne() {
    var session = sessionWithAssignedConsultant("assigned-id");
    when(groupChatMembershipService.resolveMatrixRoomId(session)).thenReturn(MATRIX_ROOM_ID);
    when(groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID))
        .thenReturn(
            List.of(
                consultantMember("assigned-id", ASSIGNED_MATRIX_ID),
                consultantMember("surplus-id", SURPLUS_MATRIX_ID)));

    removeConsultantFromSessionRoomsService.removeConsultantFromSessions(singletonList(session));

    verify(groupChatMembershipService).removeMemberFromRoom(MATRIX_ROOM_ID, SURPLUS_MATRIX_ID);
    verify(groupChatMembershipService, never())
        .removeMemberFromRoom(MATRIX_ROOM_ID, ASSIGNED_MATRIX_ID);
  }

  @Test
  void removeConsultantFromSessions_Should_NeverRemoveAskers() {
    var session = sessionWithAssignedConsultant("assigned-id");
    when(groupChatMembershipService.resolveMatrixRoomId(session)).thenReturn(MATRIX_ROOM_ID);
    when(groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID))
        .thenReturn(
            List.of(
                consultantMember("assigned-id", ASSIGNED_MATRIX_ID),
                askerMember("asker-id", "@asker:matrix.oriso.org")));

    removeConsultantFromSessionRoomsService.removeConsultantFromSessions(singletonList(session));

    verify(groupChatMembershipService, never())
        .removeMemberFromRoom(eq(MATRIX_ROOM_ID), eq("@asker:matrix.oriso.org"));
    verify(groupChatMembershipService, never())
        .removeMemberFromRoom(MATRIX_ROOM_ID, ASSIGNED_MATRIX_ID);
  }

  @Test
  void removeConsultantFromSessions_Should_DoNothing_When_RoomStateUnknown() {
    var session = sessionWithAssignedConsultant("assigned-id");
    when(groupChatMembershipService.resolveMatrixRoomId(session)).thenReturn(MATRIX_ROOM_ID);
    when(groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID)).thenReturn(List.of());

    removeConsultantFromSessionRoomsService.removeConsultantFromSessions(singletonList(session));

    verify(groupChatMembershipService, never())
        .removeMemberFromRoom(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void removeConsultantFromSessions_Should_Skip_When_SessionHasNoMatrixRoom() {
    var session = new Session();
    session.setId(2L);
    when(groupChatMembershipService.resolveMatrixRoomId(session)).thenReturn(null);

    removeConsultantFromSessionRoomsService.removeConsultantFromSessions(singletonList(session));

    verify(groupChatMembershipService, never())
        .removeMemberFromRoom(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    verify(groupChatMembershipService, never())
        .resolveHumanMembers(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void removeConsultantFromSessions_Should_DoNothing_When_NoSessions() {
    removeConsultantFromSessionRoomsService.removeConsultantFromSessions(List.of());

    verifyNoInteractions(groupChatMembershipService);
  }
}
