package de.caritas.cob.userservice.api.service.matrix;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatMembershipServiceTest {

  private static final String MATRIX_ROOM_ID = "!room:matrix.oriso.org";
  private static final String LEAVER_MATRIX_ID = "@leaver:matrix.oriso.org";
  private static final String CONSULTANT_MATRIX_ID = "@consultant:matrix.oriso.org";
  private static final String ASKER_MATRIX_ID = "@asker:matrix.oriso.org";
  private static final String AGENCY_BOT_MATRIX_ID = "@agency-1:matrix.oriso.org";
  private static final String SYSTEM_USER_MATRIX_ID = "@group-chat-system-1:matrix.oriso.org";

  @InjectMocks private GroupChatMembershipService groupChatMembershipService;

  @Mock private MatrixSynapseService matrixSynapseService;

  @Mock private ConsultantRepository consultantRepository;

  @Mock private UserRepository userRepository;

  @Mock private Consultant consultant;

  @Mock private User user;

  private Chat.ChatBuilder chatBuilder() {
    return Chat.builder()
        .id(1L)
        .topic("topic")
        .initialStartDate(java.time.LocalDateTime.now())
        .startDate(java.time.LocalDateTime.now())
        .duration(60);
  }

  private Chat chatWithMatrixRoom() {
    return chatBuilder().matrixRoomId(MATRIX_ROOM_ID).groupId(MATRIX_ROOM_ID).build();
  }

  @Test
  void hasRemainingHumanMembers_Should_ReturnTrue_When_AnotherConsultantIsInRoom() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID, CONSULTANT_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.of(consultant));

    assertTrue(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_ReturnTrue_When_AnotherAskerIsInRoom() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID, ASKER_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(ASKER_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(ASKER_MATRIX_ID))
        .thenReturn(Optional.of(user));
    when(user.getUserId()).thenReturn("asker-user-id");

    assertTrue(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_ReturnFalse_When_OnlyTheLeaverIsInRoom() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID)));

    assertFalse(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_ReturnFalse_When_RoomIsEmpty() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID)).thenReturn(Optional.of(List.of()));

    assertFalse(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_ReturnFalse_When_OnlyServiceAccountsRemain() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID, AGENCY_BOT_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(AGENCY_BOT_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(AGENCY_BOT_MATRIX_ID))
        .thenReturn(Optional.empty());

    assertFalse(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_ReturnFalse_When_OnlyGroupChatSystemUserRemains() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID, SYSTEM_USER_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(SYSTEM_USER_MATRIX_ID))
        .thenReturn(Optional.empty());
    when(userRepository.findByMatrixUserIdAndDeleteDateIsNull(SYSTEM_USER_MATRIX_ID))
        .thenReturn(Optional.of(user));
    when(user.getUserId()).thenReturn("group-chat-system-1");

    assertFalse(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_FailSafeToTrue_When_MemberQueryFails() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID)).thenReturn(Optional.empty());

    assertTrue(
        groupChatMembershipService.hasRemainingHumanMembers(
            chatWithMatrixRoom(), LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_FailSafeToTrue_When_ChatHasNoMatrixRoom() {
    var legacyChat = chatBuilder().groupId("rcGroupId4711").build();

    assertTrue(groupChatMembershipService.hasRemainingHumanMembers(legacyChat, LEAVER_MATRIX_ID));
    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void hasRemainingHumanMembers_Should_FallBackToGroupId_When_ItIsAMatrixRoomId() {
    var chat = chatBuilder().groupId(MATRIX_ROOM_ID).build();
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(LEAVER_MATRIX_ID)));

    assertFalse(groupChatMembershipService.hasRemainingHumanMembers(chat, LEAVER_MATRIX_ID));
  }

  @Test
  void hasRemainingHumanMembers_Should_CountAllHumans_When_LeaverIdIsUnknown() {
    when(matrixSynapseService.getRoomMembers(MATRIX_ROOM_ID))
        .thenReturn(Optional.of(List.of(CONSULTANT_MATRIX_ID)));
    when(consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(CONSULTANT_MATRIX_ID))
        .thenReturn(Optional.of(consultant));

    assertTrue(groupChatMembershipService.hasRemainingHumanMembers(chatWithMatrixRoom(), null));
  }

  @Test
  void removeLeavingMemberFromRoom_Should_LeaveRoomWithLeaversOwnToken() {
    when(matrixSynapseService.loginAsUserAccessToken(LEAVER_MATRIX_ID)).thenReturn("token");
    when(matrixSynapseService.leaveRoom(MATRIX_ROOM_ID, "token")).thenReturn(true);

    groupChatMembershipService.removeLeavingMemberFromRoom(chatWithMatrixRoom(), LEAVER_MATRIX_ID);

    verify(matrixSynapseService).leaveRoom(MATRIX_ROOM_ID, "token");
  }

  @Test
  void removeLeavingMemberFromRoom_Should_DoNothing_When_LeaverHasNoMatrixId() {
    groupChatMembershipService.removeLeavingMemberFromRoom(chatWithMatrixRoom(), null);

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void removeLeavingMemberFromRoom_Should_DoNothing_When_ChatHasNoMatrixRoom() {
    var legacyChat = chatBuilder().groupId("rcGroupId4711").build();

    groupChatMembershipService.removeLeavingMemberFromRoom(legacyChat, LEAVER_MATRIX_ID);

    verifyNoInteractions(matrixSynapseService);
  }

  @Test
  void removeLeavingMemberFromRoom_Should_NotThrow_When_TokenMintingFails() {
    when(matrixSynapseService.loginAsUserAccessToken(LEAVER_MATRIX_ID)).thenReturn(null);

    groupChatMembershipService.removeLeavingMemberFromRoom(chatWithMatrixRoom(), LEAVER_MATRIX_ID);

    verify(matrixSynapseService, never()).leaveRoom(anyString(), any());
  }

  @Test
  void removeLeavingMemberFromRoom_Should_NotThrow_When_MatrixCallFails() {
    when(matrixSynapseService.loginAsUserAccessToken(LEAVER_MATRIX_ID))
        .thenThrow(new RuntimeException("synapse down"));

    groupChatMembershipService.removeLeavingMemberFromRoom(chatWithMatrixRoom(), LEAVER_MATRIX_ID);

    verify(matrixSynapseService, never()).leaveRoom(anyString(), any());
  }
}
