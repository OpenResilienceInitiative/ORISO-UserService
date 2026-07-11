package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.ACTIVE_CHAT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.INACTIVE_CHAT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_GROUP_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatAddUserToGroupException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.chat.GroupChatPermissionService;
import de.caritas.cob.userservice.api.service.notification.GroupChatLifecycleNotificationService;
import de.caritas.cob.userservice.api.service.notification.GroupChatNotificationRecipientService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class StartChatFacadeTest {

  @InjectMocks private StartChatFacade startChatFacade;

  @Mock private GroupChatPermissionService groupChatPermissionService;

  @Mock private RocketChatService rocketChatService;

  @Mock private ChatService chatService;

  @Mock private GroupChatLifecycleNotificationService groupChatLifecycleNotificationService;

  @Mock private GroupChatNotificationRecipientService notificationRecipientService;

  @Mock private Chat chat;

  @Test
  public void
      startChat_Should_ThrowRequestForbiddenException_WhenConsultantHasNoPermissionForChat() {
    doThrow(new ForbiddenException("forbidden"))
        .when(groupChatPermissionService)
        .requireCanModerate(ACTIVE_CHAT, CONSULTANT);

    try {
      startChatFacade.startChat(ACTIVE_CHAT, CONSULTANT);
      fail("Expected exception: RequestForbiddenException");
    } catch (ForbiddenException sequestForbiddenException) {
      assertTrue(true, "Excepted RequestForbiddenException thrown");
    }
  }

  @Test
  public void startChat_Should_ThrowConflictException_WhenChatIsAlreadyStarted() {
    try {
      startChatFacade.startChat(ACTIVE_CHAT, CONSULTANT);
      fail("Expected exception: ConflictException");
    } catch (ConflictException conflictException) {
      assertTrue(true, "Excepted ConflictException thrown");
    }
  }

  @Test
  public void startChat_Should_ThrowInternalServerError_WhenChatHasNoGroupId() {
    when(chat.isActive()).thenReturn(false);
    when(chat.getGroupId()).thenReturn(null);

    try {
      startChatFacade.startChat(chat, CONSULTANT);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException internalServerErrorException) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }
  }

  @Test
  public void startChat_Should_AddConsultantToRocketChatGroup()
      throws RocketChatAddUserToGroupException {
    startChatFacade.startChat(INACTIVE_CHAT, CONSULTANT);

    verify(rocketChatService, times(1))
        .addUserToGroup(CONSULTANT.getRocketChatId(), INACTIVE_CHAT.getGroupId());
  }

  @Test
  public void startChat_Should_SetChatActiveAndSaveChat() {
    when(chat.getGroupId()).thenReturn(RC_GROUP_ID);
    startChatFacade.startChat(chat, CONSULTANT);

    verify(chat, times(1)).setActive(true);
    verify(chatService, times(1)).saveChat(chat);
  }

  @Test
  public void startChat_Should_NotCallRocketChat_WhenGroupIdIsMatrixRoom() {
    when(chat.isActive()).thenReturn(false);
    when(chat.getGroupId()).thenReturn("!room:matrix.local");
    startChatFacade.startChat(chat, CONSULTANT);

    verifyNoInteractions(rocketChatService);
    verify(chat).setActive(true);
    verify(chatService).saveChat(chat);
  }

  @Test
  public void startChat_Should_AllowMatrixRoomIdWithoutLegacyGroupId() {
    when(chat.isActive()).thenReturn(false);
    when(chat.getGroupId()).thenReturn(null);
    when(chat.getMatrixRoomId()).thenReturn("!room:matrix.local");
    startChatFacade.startChat(chat, CONSULTANT);

    verifyNoInteractions(rocketChatService);
    verify(chat).setActive(true);
    verify(chatService).saveChat(chat);
  }

  @Test
  public void startChat_Should_PublishOpenedEventForMatrixSeriesParticipants() {
    when(chat.isActive()).thenReturn(false);
    when(chat.getId()).thenReturn(42L);
    when(chat.getGroupId()).thenReturn("!room:matrix.local");
    when(chat.getMatrixRoomId()).thenReturn("!room:matrix.local");
    when(chat.getCurrentOccurrenceIndex()).thenReturn(0);
    when(chat.getStartDate()).thenReturn(java.time.LocalDateTime.parse("2026-08-03T18:00:00"));
    when(chat.getChatModality()).thenReturn(Chat.ChatModality.TEXT);
    when(notificationRecipientService.resolveRecipientIds(chat))
        .thenReturn(java.util.List.of("consultant-1", "asker-1"));

    startChatFacade.startChat(chat, CONSULTANT);

    verify(groupChatLifecycleNotificationService)
        .createOpenedNotifications(
            42L,
            0,
            java.time.LocalDateTime.parse("2026-08-03T18:00:00"),
            "!room:matrix.local",
            null,
            false,
            java.util.List.of("consultant-1", "asker-1"));
  }

  @Test
  void startChatShouldRemainSuccessfulWhenOpenedNotificationFails() {
    when(chat.isActive()).thenReturn(false);
    when(chat.getId()).thenReturn(42L);
    when(chat.getGroupId()).thenReturn("!room:matrix.local");
    when(chat.getMatrixRoomId()).thenReturn("!room:matrix.local");
    when(chat.getChatModality()).thenReturn(Chat.ChatModality.TEXT);
    when(notificationRecipientService.resolveRecipientIds(chat))
        .thenReturn(java.util.List.of("consultant-1"));
    doThrow(new IllegalStateException("notification storage unavailable"))
        .when(groupChatLifecycleNotificationService)
        .createOpenedNotifications(any(), any(), any(), any(), any(), any(), any());

    assertDoesNotThrow(() -> startChatFacade.startChat(chat, CONSULTANT));

    verify(chat).setActive(true);
    verify(chatService).saveChat(chat);
  }

  @Test
  public void
      startChat_Should_throwInternalServerErrorException_When_userCanNotBeAddedToGroupInRocketChat()
          throws RocketChatAddUserToGroupException {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          when(chat.getGroupId()).thenReturn(RC_GROUP_ID);
          doThrow(new RocketChatAddUserToGroupException(""))
              .when(rocketChatService)
              .addUserToGroup(any(), any());

          startChatFacade.startChat(chat, CONSULTANT);

          verify(chat, times(1)).setActive(true);
          verify(chatService, times(1)).saveChat(chat);
        });
  }
}
