package de.caritas.cob.userservice.api.actions.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ChatReCreatorTest {

  private static final String OLD_MATRIX_ROOM_ID = "!oldroom:matrix.local";
  private static final String NEW_MATRIX_ROOM_ID = "!newroom:matrix.local";
  private static final String OWNER_MATRIX_USER_ID = "@owner:matrix.local";
  private static final LocalDateTime START_DATE = LocalDateTime.of(2026, 7, 1, 14, 0);

  @InjectMocks private ChatReCreator chatReCreator;

  @Mock private ChatService chatService;

  @Mock private MatrixSynapseService matrixSynapseService;

  @Mock private MatrixChatShutdownService matrixChatShutdownService;

  @Mock private GroupChatMembershipService groupChatMembershipService;

  @BeforeEach
  void resolveRoomIdFromChatFixture() {
    lenient()
        .when(groupChatMembershipService.resolveMatrixRoomId(any(Chat.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Chat.class).getMatrixRoomId());
  }

  @Test
  void recreateShouldCreateMatrixRoomAsChatOwnerAndShutDownOldRoomAfterwards()
      throws MatrixCreateRoomException {
    var chat = buildRepetitiveChat(OWNER_MATRIX_USER_ID);
    var response = new MatrixCreateRoomResponseDTO();
    response.setRoomId(NEW_MATRIX_ROOM_ID);
    when(matrixSynapseService.createRoomAsMatrixUser(
            eq("topic"), startsWith("group_chat_"), eq(OWNER_MATRIX_USER_ID)))
        .thenReturn(ResponseEntity.ok(response));
    when(groupChatMembershipService.resolveHumanMembers(OLD_MATRIX_ROOM_ID))
        .thenReturn(
            List.of(
                new ResolvedRoomMember(
                    OWNER_MATRIX_USER_ID, "owner-id", "owner", "Owner Owner", true)));

    var newRoomId = chatReCreator.recreateMessengerChat(chat);

    assertEquals(NEW_MATRIX_ROOM_ID, newRoomId);
    var order = inOrder(matrixSynapseService, matrixChatShutdownService);
    order
        .verify(matrixSynapseService)
        .createRoomAsMatrixUser(eq("topic"), startsWith("group_chat_"), eq(OWNER_MATRIX_USER_ID));
    order.verify(matrixChatShutdownService).shutdownRoom(chat);
  }

  @Test
  void recreateShouldCarryEveryHumanMemberIntoNewRoomBeforeShuttingDownOldRoom()
      throws MatrixCreateRoomException {
    var chat = buildRepetitiveChat(OWNER_MATRIX_USER_ID);
    var coModeratorMatrixId = "@co-moderator:matrix.local";
    var askerMatrixId = "@asker:matrix.local";
    var response = new MatrixCreateRoomResponseDTO();
    response.setRoomId(NEW_MATRIX_ROOM_ID);
    when(groupChatMembershipService.resolveHumanMembers(OLD_MATRIX_ROOM_ID))
        .thenReturn(
            List.of(
                new ResolvedRoomMember(
                    OWNER_MATRIX_USER_ID, "owner-id", "owner", "Owner Owner", true),
                new ResolvedRoomMember(coModeratorMatrixId, "co-id", "co", "Co Moderator", true),
                new ResolvedRoomMember(askerMatrixId, "asker-id", "asker", "asker", false)));
    when(matrixSynapseService.createRoomAsMatrixUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(response));
    when(groupChatMembershipService.addMemberToRoom(
            NEW_MATRIX_ROOM_ID, OWNER_MATRIX_USER_ID, coModeratorMatrixId))
        .thenReturn(true);
    when(groupChatMembershipService.addMemberToRoom(
            NEW_MATRIX_ROOM_ID, OWNER_MATRIX_USER_ID, askerMatrixId))
        .thenReturn(true);

    chatReCreator.recreateMessengerChat(chat);

    verify(groupChatMembershipService, never())
        .addMemberToRoom(NEW_MATRIX_ROOM_ID, OWNER_MATRIX_USER_ID, OWNER_MATRIX_USER_ID);
    var order = inOrder(groupChatMembershipService, matrixChatShutdownService);
    order
        .verify(groupChatMembershipService)
        .addMemberToRoom(NEW_MATRIX_ROOM_ID, OWNER_MATRIX_USER_ID, coModeratorMatrixId);
    order
        .verify(groupChatMembershipService)
        .addMemberToRoom(NEW_MATRIX_ROOM_ID, OWNER_MATRIX_USER_ID, askerMatrixId);
    order.verify(matrixChatShutdownService).shutdownRoom(chat);
  }

  @Test
  void recreateShouldKeepOldRoomAliveWhenAnyHumanMemberCannotJoinNewRoom()
      throws MatrixCreateRoomException {
    var chat = buildRepetitiveChat(OWNER_MATRIX_USER_ID);
    var askerMatrixId = "@asker:matrix.local";
    var response = new MatrixCreateRoomResponseDTO();
    response.setRoomId(NEW_MATRIX_ROOM_ID);
    when(groupChatMembershipService.resolveHumanMembers(OLD_MATRIX_ROOM_ID))
        .thenReturn(
            List.of(
                new ResolvedRoomMember(
                    OWNER_MATRIX_USER_ID, "owner-id", "owner", "Owner Owner", true),
                new ResolvedRoomMember(askerMatrixId, "asker-id", "asker", "asker", false)));
    when(matrixSynapseService.createRoomAsMatrixUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(response));
    when(groupChatMembershipService.addMemberToRoom(
            NEW_MATRIX_ROOM_ID, OWNER_MATRIX_USER_ID, askerMatrixId))
        .thenReturn(false);

    assertThrows(
        InternalServerErrorException.class, () -> chatReCreator.recreateMessengerChat(chat));

    verifyNoInteractions(matrixChatShutdownService);
  }

  @Test
  void recreateShouldFailAndKeepOldRoomAliveWhenMatrixRoomCreationFails()
      throws MatrixCreateRoomException {
    var chat = buildRepetitiveChat(OWNER_MATRIX_USER_ID);
    when(groupChatMembershipService.resolveHumanMembers(OLD_MATRIX_ROOM_ID))
        .thenReturn(ownerRoomMembers());
    when(matrixSynapseService.createRoomAsMatrixUser(anyString(), anyString(), anyString()))
        .thenThrow(new MatrixCreateRoomException("Synapse unavailable"));

    assertThrows(
        InternalServerErrorException.class, () -> chatReCreator.recreateMessengerChat(chat));

    verifyNoInteractions(matrixChatShutdownService);
    verifyNoInteractions(chatService);
  }

  @Test
  void recreateShouldFailWhenMatrixResponseContainsNoRoomId() throws MatrixCreateRoomException {
    var chat = buildRepetitiveChat(OWNER_MATRIX_USER_ID);
    when(groupChatMembershipService.resolveHumanMembers(OLD_MATRIX_ROOM_ID))
        .thenReturn(ownerRoomMembers());
    when(matrixSynapseService.createRoomAsMatrixUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(new MatrixCreateRoomResponseDTO()));

    assertThrows(
        InternalServerErrorException.class, () -> chatReCreator.recreateMessengerChat(chat));

    verifyNoInteractions(matrixChatShutdownService);
  }

  @Test
  void recreateShouldFailWhenChatOwnerHasNoMatrixUser() {
    var chat = buildRepetitiveChat(null);

    assertThrows(
        InternalServerErrorException.class, () -> chatReCreator.recreateMessengerChat(chat));

    verifyNoInteractions(matrixSynapseService);
    verifyNoInteractions(matrixChatShutdownService);
  }

  @Test
  void updateAsNextChatShouldPersistNewRoomIdOnBothColumnsAndResetChatState() {
    var chat = buildRepetitiveChat(OWNER_MATRIX_USER_ID);

    chatReCreator.updateAsNextChat(chat, NEW_MATRIX_ROOM_ID);

    assertEquals(NEW_MATRIX_ROOM_ID, chat.getGroupId());
    assertEquals(NEW_MATRIX_ROOM_ID, chat.getMatrixRoomId());
    assertEquals(START_DATE.plusWeeks(1), chat.getStartDate());
    assertEquals(1, chat.getCurrentOccurrenceIndex());
    assertFalse(chat.isActive());
    assertNotNull(chat.getUpdateDate());
    assertEquals(ConversationType.SELF_HELP, chat.getConversationType());
    verify(chatService).saveChat(chat);
  }

  private Chat buildRepetitiveChat(String ownerMatrixUserId) {
    var owner =
        Consultant.builder()
            .id("owner-id")
            .rocketChatId("rc-owner")
            .username("owner")
            .firstName("Owner")
            .lastName("Owner")
            .email("owner@oriso.local")
            .matrixUserId(ownerMatrixUserId)
            .build();
    var chat =
        Chat.builder()
            .topic("topic")
            .consultingTypeId(15)
            .initialStartDate(START_DATE)
            .startDate(START_DATE)
            .duration(60)
            .repetitive(true)
            .repeatCount(2)
            .chatInterval(ChatInterval.WEEKLY)
            .conversationType(ConversationType.SELF_HELP)
            .chatOwner(owner)
            .build();
    chat.setActive(true);
    chat.setGroupId(OLD_MATRIX_ROOM_ID);
    chat.setMatrixRoomId(OLD_MATRIX_ROOM_ID);
    return chat;
  }

  private List<ResolvedRoomMember> ownerRoomMembers() {
    return List.of(
        new ResolvedRoomMember(OWNER_MATRIX_USER_ID, "owner-id", "owner", "Owner Owner", true));
  }
}
