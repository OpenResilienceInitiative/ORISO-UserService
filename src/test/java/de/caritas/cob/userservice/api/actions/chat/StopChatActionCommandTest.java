package de.caritas.cob.userservice.api.actions.chat;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_INTERVAL_WEEKLY;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_START_DATETIME;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.IS_REPETITIVE;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_GROUP_ID;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatAddUserToGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatCreateGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatRemoveSystemMessagesException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatUserNotInitializedException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.service.ChatService;
import java.util.Set;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class StopChatActionCommandTest {

  private static final String MATRIX_ROOM_ID = "!room:matrix.local";

  private StopChatActionCommand stopChatActionCommand;

  @Mock private ChatService chatService;

  @Mock private RocketChatService rocketChatService;

  @Mock private MatrixSynapseService matrixSynapseService;

  @Mock private Chat chat;

  @Mock private ChatReCreator chatReCreator;

  @BeforeEach
  void setUp() {
    stopChatActionCommand =
        new StopChatActionCommand(
            chatService,
            rocketChatService,
            chatReCreator,
            new MatrixChatShutdownService(matrixSynapseService));
  }

  @Test
  void stopChat_Should_ThrowConflictException_When_ChatIsAlreadyStopped() {
    when(chat.isActive()).thenReturn(false);

    try {
      stopChatActionCommand.execute(chat);
      fail("Expected exception: ConflictException");
    } catch (ConflictException conflictException) {
      assertTrue(true, "Excepted ConflictException thrown");
    }
  }

  @Test
  void stopChat_Should_ThrowInternalServerError_When_ChatHasNoGroupId() {
    when(chat.isActive()).thenReturn(true);
    when(chat.getGroupId()).thenReturn(null);

    try {
      stopChatActionCommand.execute(chat);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException internalServerErrorException) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }
  }

  @Test
  void stopChat_Should_ThrowInternalServerError_When_DeleteRcGroupFails()
      throws RocketChatRemoveSystemMessagesException {
    when(chat.isActive()).thenReturn(true);
    when(chat.isRepetitive()).thenReturn(false);
    when(chat.getGroupId()).thenReturn(RC_GROUP_ID);
    when(rocketChatService.deleteGroupAsSystemUser(chat.getGroupId())).thenReturn(false);

    try {
      stopChatActionCommand.execute(chat);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException internalServerErrorException) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }

    verify(rocketChatService, times(0)).removeAllMessages(Mockito.any());
    verify(rocketChatService, times(1)).deleteGroupAsSystemUser(Mockito.any());
  }

  @Test
  void stopChat_Should_NotDeleteGroup_When_ChatIntervallIsNullOnRepetitiveChats() {
    when(chat.isActive()).thenReturn(true);
    when(chat.getGroupId()).thenReturn(RC_GROUP_ID);
    when(chat.isRepetitive()).thenReturn(true);

    stopChatActionCommand.execute(chat);

    verify(rocketChatService, times(0)).deleteGroupAsSystemUser(Mockito.any());
  }

  @Test
  void stopChatShouldDeleteChatGroupAndRecreateRespUpdateWhenRepetitive() {
    Chat chatWithDate =
        Chat.builder()
            .topic("topic")
            .consultingTypeId(15)
            .initialStartDate(CHAT_START_DATETIME)
            .startDate(CHAT_START_DATETIME)
            .duration(1)
            .repetitive(IS_REPETITIVE)
            .chatInterval(CHAT_INTERVAL_WEEKLY)
            .chatOwner(CONSULTANT)
            .build();
    chatWithDate.setActive(true);
    chatWithDate.setGroupId("groupId");
    chatWithDate.setChatAgencies(Set.of(new ChatAgency(chat, 1L)));
    when(rocketChatService.deleteGroupAsSystemUser("groupId")).thenReturn(true);
    var groupId = RandomStringUtils.randomAlphanumeric(17);
    when(chatReCreator.recreateMessengerChat(any(Chat.class))).thenReturn(groupId);

    stopChatActionCommand.execute(chatWithDate);

    verify(rocketChatService).deleteGroupAsSystemUser("groupId");
    verify(chatService, never()).deleteChat(chatWithDate);
    verify(chatReCreator).recreateMessengerChat(chatWithDate);
  }

  @Test
  void stopChatShouldDeleteChatGroupAndNotRecreateWhenNotRepetitive()
      throws RocketChatCreateGroupException,
          RocketChatUserNotInitializedException,
          RocketChatAddUserToGroupException {
    Chat chatWithDate =
        Chat.builder()
            .topic("topic")
            .consultingTypeId(15)
            .initialStartDate(CHAT_START_DATETIME)
            .startDate(CHAT_START_DATETIME)
            .duration(1)
            .repetitive(false)
            .chatInterval(CHAT_INTERVAL_WEEKLY)
            .chatOwner(CONSULTANT)
            .build();
    chatWithDate.setActive(true);
    chatWithDate.setGroupId("groupId");
    chatWithDate.setChatAgencies(Set.of(new ChatAgency(chat, 1L)));
    when(rocketChatService.deleteGroupAsSystemUser("groupId")).thenReturn(true);

    stopChatActionCommand.execute(chatWithDate);

    verify(rocketChatService).deleteGroupAsSystemUser("groupId");
    verify(chatService).deleteChat(chatWithDate);
    verify(rocketChatService, never()).createPrivateGroupWithSystemUser(anyString());
    verify(rocketChatService, never()).addTechnicalUserToGroup(anyString());
    verify(chatService, never()).saveChatAgencyRelation(any(ChatAgency.class));
    verify(chatService, never()).saveChat(any(Chat.class));
  }

  @Test
  void stopChat_Should_RemoveRocketChatGroupAndChatFromDb_When_ChatIsNotRepetitive() {
    when(chat.isActive()).thenReturn(true);
    when(chat.isRepetitive()).thenReturn(false);
    when(chat.getGroupId()).thenReturn(RC_GROUP_ID);

    when(rocketChatService.deleteGroupAsSystemUser(RC_GROUP_ID)).thenReturn(true);

    stopChatActionCommand.execute(chat);

    verify(rocketChatService, times(1)).deleteGroupAsSystemUser(chat.getGroupId());
    verify(chatService, times(1)).deleteChat(chat);
  }

  @Test
  void stopChatShouldShutDownMatrixRoomWhenNonRepetitiveChatHasMatrixRoomId() {
    when(chat.isActive()).thenReturn(true);
    when(chat.isRepetitive()).thenReturn(false);
    when(chat.getGroupId()).thenReturn(RC_GROUP_ID);
    when(chat.getMatrixRoomId()).thenReturn(MATRIX_ROOM_ID);
    when(rocketChatService.deleteGroupAsSystemUser(RC_GROUP_ID)).thenReturn(true);
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID)).thenReturn(true);

    stopChatActionCommand.execute(chat);

    verify(matrixSynapseService).purgeRoom(MATRIX_ROOM_ID);
    verify(chatService).deleteChat(chat);
  }

  @Test
  void stopChatShouldNotCallMatrixWhenChatHasNoMatrixRoomId() {
    when(chat.isActive()).thenReturn(true);
    when(chat.isRepetitive()).thenReturn(false);
    when(chat.getGroupId()).thenReturn(RC_GROUP_ID);
    when(chat.getMatrixRoomId()).thenReturn(null);
    when(rocketChatService.deleteGroupAsSystemUser(RC_GROUP_ID)).thenReturn(true);

    stopChatActionCommand.execute(chat);

    verifyNoInteractions(matrixSynapseService);
    verify(chatService).deleteChat(chat);
  }

  @Test
  void stopChatShouldStillSucceedWhenMatrixRoomShutdownReportsFailure() {
    when(chat.isActive()).thenReturn(true);
    when(chat.isRepetitive()).thenReturn(false);
    when(chat.getGroupId()).thenReturn(RC_GROUP_ID);
    when(chat.getMatrixRoomId()).thenReturn(MATRIX_ROOM_ID);
    when(rocketChatService.deleteGroupAsSystemUser(RC_GROUP_ID)).thenReturn(true);
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID)).thenReturn(false);

    stopChatActionCommand.execute(chat);

    verify(chatService).deleteChat(chat);
  }

  @Test
  void stopChatShouldStillSucceedWhenMatrixCallThrows() {
    when(chat.isActive()).thenReturn(true);
    when(chat.isRepetitive()).thenReturn(false);
    when(chat.getGroupId()).thenReturn(RC_GROUP_ID);
    when(chat.getMatrixRoomId()).thenReturn(MATRIX_ROOM_ID);
    when(rocketChatService.deleteGroupAsSystemUser(RC_GROUP_ID)).thenReturn(true);
    when(matrixSynapseService.purgeRoom(MATRIX_ROOM_ID))
        .thenThrow(new RestClientException("Synapse unavailable"));

    stopChatActionCommand.execute(chat);

    verify(chatService).deleteChat(chat);
  }

  @Test
  void stopChatShouldNotShutDownMatrixRoomWhenChatIsRepetitive() {
    Chat repetitiveChat =
        Chat.builder()
            .topic("topic")
            .consultingTypeId(15)
            .initialStartDate(CHAT_START_DATETIME)
            .startDate(CHAT_START_DATETIME)
            .duration(1)
            .repetitive(IS_REPETITIVE)
            .chatInterval(CHAT_INTERVAL_WEEKLY)
            .chatOwner(CONSULTANT)
            .build();
    repetitiveChat.setActive(true);
    repetitiveChat.setGroupId("groupId");
    repetitiveChat.setMatrixRoomId(MATRIX_ROOM_ID);
    when(rocketChatService.deleteGroupAsSystemUser("groupId")).thenReturn(true);
    when(chatReCreator.recreateMessengerChat(any(Chat.class)))
        .thenReturn(RandomStringUtils.randomAlphanumeric(17));

    stopChatActionCommand.execute(repetitiveChat);

    verifyNoInteractions(matrixSynapseService);
    verify(chatReCreator).recreateMessengerChat(repetitiveChat);
  }
}
