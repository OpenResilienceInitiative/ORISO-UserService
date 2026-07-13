package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.ACTIVE_CHAT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_USER_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.actions.chat.ChatReCreator;
import de.caritas.cob.userservice.api.actions.chat.MatrixChatShutdownService;
import de.caritas.cob.userservice.api.adapters.rocketchat.DisabledRocketChatService;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentialsProvider;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatMapper;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatAddUserToGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatRemoveUserFromGroupException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.chat.GroupChatRoleService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JoinAndLeaveChatFacadeTest {

  private static final String MATRIX_USER_ID = "@leaver:matrix.oriso.org";

  @InjectMocks private JoinAndLeaveChatFacade joinAndLeaveChatFacade;

  @Mock private ChatService chatService;

  @Mock private AuthenticatedUser authenticatedUser;

  @Mock private ChatPermissionVerifier chatPermissionVerifier;

  @Mock private ConsultantService consultantService;

  @Mock private UserService userService;

  @Mock private User user;

  @Mock private Consultant consultant;

  @Mock private RocketChatService rocketChatService;

  @Mock private ChatReCreator chatReCreator;

  @Mock private GroupChatMembershipService groupChatMembershipService;

  @Mock private MatrixChatShutdownService matrixChatShutdownService;

  @Mock private GroupChatRoleService groupChatRoleService;

  @Test
  void joinChat_Should_ThrowNotFoundException_WhenChatDoesNotExist() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.empty());

    try {
      joinAndLeaveChatFacade.joinChat(CHAT_ID, authenticatedUser);
      fail("Expected exception: NotFoundException");
    } catch (NotFoundException notFoundException) {
      assertTrue(true, "Excepted NotFoundException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
  }

  @Test
  void joinChat_Should_ThrowConflictException_WhenChatIsNotActive() {
    Chat inactiveChat = mock(Chat.class);
    when(inactiveChat.isActive()).thenReturn(false);

    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(inactiveChat));

    try {
      joinAndLeaveChatFacade.joinChat(CHAT_ID, authenticatedUser);
      fail("Expected exception: ConflictException");
    } catch (ConflictException conflictException) {
      assertTrue(true, "Excepted ConflictException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
  }

  @Test
  void joinChat_Should_ThrowRequestForbiddenException_WhenConsultantHasNoPermissionForChat() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    doThrow(new ForbiddenException(""))
        .when(chatPermissionVerifier)
        .verifyPermissionForChat(ACTIVE_CHAT);

    try {
      joinAndLeaveChatFacade.joinChat(CHAT_ID, authenticatedUser);
      fail("Expected exception: RequestForbiddenException");
    } catch (ForbiddenException requestForbiddenException) {
      assertTrue(true, "Excepted RequestForbiddenException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
  }

  @Test
  void joinChat_Should_ThrowRequestForbiddenException_WhenUserHasNoPermissionForChat() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    doThrow(new ForbiddenException(""))
        .when(chatPermissionVerifier)
        .verifyPermissionForChat(ACTIVE_CHAT);

    try {
      joinAndLeaveChatFacade.joinChat(CHAT_ID, authenticatedUser);
      fail("Expected exception: RequestForbiddenException");
    } catch (ForbiddenException requestForbiddenException) {
      assertTrue(true, "Excepted RequestForbiddenException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
  }

  @Test
  void joinChat_Should_ThrowInternalServerErrorException_WhenConsultantHasNoRocketChatId() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(consultantService.getConsultantViaAuthenticatedUser(authenticatedUser))
        .thenReturn(Optional.of(consultant));
    when(consultant.getRocketChatId()).thenReturn(null);

    try {
      joinAndLeaveChatFacade.joinChat(CHAT_ID, authenticatedUser);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException internalServerErrorException) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
    verify(consultantService, times(1)).getConsultantViaAuthenticatedUser(authenticatedUser);
    verify(consultant, times(1)).getRocketChatId();
  }

  @Test
  void joinChat_Should_ThrowInternalServerErrorException_WhenUserHasNoRocketChatId() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getRcUserId()).thenReturn(null);

    try {
      joinAndLeaveChatFacade.joinChat(CHAT_ID, authenticatedUser);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException internalServerErrorException) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
    verify(user, times(1)).getRcUserId();
  }

  @Test
  void joinChat_Should_AddConsultantToRocketChatGroup() throws RocketChatAddUserToGroupException {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(consultantService.getConsultantViaAuthenticatedUser(authenticatedUser))
        .thenReturn(Optional.of(CONSULTANT));

    joinAndLeaveChatFacade.joinChat(ACTIVE_CHAT.getId(), authenticatedUser);

    verify(rocketChatService, times(1))
        .addUserToGroup(CONSULTANT.getRocketChatId(), ACTIVE_CHAT.getGroupId());
  }

  @Test
  void joinChat_Should_AddUserToRocketChatGroup() throws RocketChatAddUserToGroupException {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getRcUserId()).thenReturn(RC_USER_ID);

    joinAndLeaveChatFacade.joinChat(ACTIVE_CHAT.getId(), authenticatedUser);

    verify(rocketChatService, times(1)).addUserToGroup(RC_USER_ID, ACTIVE_CHAT.getGroupId());
  }

  @Test
  void joinChat_Should_JoinMatrixUser_WhenRocketChatIdIsMissing()
      throws RocketChatAddUserToGroupException {
    Chat matrixChat = mock(Chat.class);
    when(matrixChat.isActive()).thenReturn(true);
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(matrixChat));
    when(groupChatMembershipService.resolveMatrixRoomId(matrixChat))
        .thenReturn("!room:matrix.oriso.org");
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getMatrixUserId()).thenReturn(MATRIX_USER_ID);
    when(groupChatMembershipService.addMemberToRoom(matrixChat, MATRIX_USER_ID)).thenReturn(true);

    joinAndLeaveChatFacade.joinChat(CHAT_ID, authenticatedUser);

    verify(groupChatMembershipService).addMemberToRoom(matrixChat, MATRIX_USER_ID);
    verify(rocketChatService, never()).addUserToGroup(anyString(), anyString());
  }

  @Test
  void leaveChat_Should_ThrowNotFoundException_WhenChatDoesNotExist() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.empty());

    try {
      joinAndLeaveChatFacade.leaveChat(CHAT_ID, authenticatedUser);
      fail("Expected exception: NotFoundException");
    } catch (NotFoundException notFoundException) {
      assertTrue(true, "Excepted NotFoundException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
  }

  @Test
  void leaveChat_Should_ThrowConflictException_WhenChatIsNotActive() {
    Chat inactiveChat = mock(Chat.class);
    when(inactiveChat.isActive()).thenReturn(false);

    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(inactiveChat));

    try {
      joinAndLeaveChatFacade.leaveChat(CHAT_ID, authenticatedUser);
      fail("Expected exception: ConflictException");
    } catch (ConflictException conflictException) {
      assertTrue(true, "Excepted ConflictException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
  }

  @Test
  void leaveChat_Should_ThrowRequestForbiddenException_WhenConsultantHasNoPermissionForChat() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    doThrow(new ForbiddenException(""))
        .when(chatPermissionVerifier)
        .verifyPermissionForChat(ACTIVE_CHAT);

    try {
      joinAndLeaveChatFacade.leaveChat(CHAT_ID, authenticatedUser);
      fail("Expected exception: RequestForbiddenException");
    } catch (ForbiddenException requestForbiddenException) {
      assertTrue(true, "Excepted RequestForbiddenException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
  }

  @Test
  void leaveChat_Should_ThrowRequestForbiddenException_WhenUserHasNoPermissionForChat() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    doThrow(new ForbiddenException(""))
        .when(chatPermissionVerifier)
        .verifyPermissionForChat(ACTIVE_CHAT);

    try {
      joinAndLeaveChatFacade.leaveChat(CHAT_ID, authenticatedUser);
      fail("Expected exception: RequestForbiddenException");
    } catch (ForbiddenException requestForbiddenException) {
      assertTrue(true, "Excepted RequestForbiddenException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
  }

  @Test
  void leaveChat_Should_ThrowInternalServerErrorException_WhenConsultantHasNoRocketChatId() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(consultantService.getConsultantViaAuthenticatedUser(authenticatedUser))
        .thenReturn(Optional.of(consultant));
    when(consultant.getRocketChatId()).thenReturn(null);

    try {
      joinAndLeaveChatFacade.leaveChat(CHAT_ID, authenticatedUser);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException internalServerErrorException) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
    verify(consultantService, times(1)).getConsultantViaAuthenticatedUser(authenticatedUser);
    verify(consultant, times(1)).getRocketChatId();
  }

  @Test
  void leaveChat_Should_ThrowInternalServerErrorException_WhenUserHasNoRocketChatId() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getRcUserId()).thenReturn(null);

    try {
      joinAndLeaveChatFacade.leaveChat(CHAT_ID, authenticatedUser);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException internalServerErrorException) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
    verify(user, times(1)).getRcUserId();
  }

  @Test
  void leaveChat_Should_RemoveConsultantFromRocketChatGroup()
      throws RocketChatRemoveUserFromGroupException {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(consultantService.getConsultantViaAuthenticatedUser(authenticatedUser))
        .thenReturn(Optional.of(CONSULTANT));
    when(groupChatMembershipService.hasRemainingHumanMembers(eq(ACTIVE_CHAT), any()))
        .thenReturn(true);

    joinAndLeaveChatFacade.leaveChat(ACTIVE_CHAT.getId(), authenticatedUser);

    verify(rocketChatService, times(1))
        .removeUserFromGroup(CONSULTANT.getRocketChatId(), ACTIVE_CHAT.getGroupId());
  }

  @Test
  void leaveChat_Should_throwInternalServerErrorException_When_rocketChatUserCanNotBeRemoved()
      throws RocketChatRemoveUserFromGroupException {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
          when(userService.getUserViaAuthenticatedUser(authenticatedUser))
              .thenReturn(Optional.of(user));
          when(user.getRcUserId()).thenReturn(RC_USER_ID);
          doThrow(new RocketChatRemoveUserFromGroupException(""))
              .when(rocketChatService)
              .removeUserFromGroup(any(), any());

          joinAndLeaveChatFacade.leaveChat(ACTIVE_CHAT.getId(), authenticatedUser);
        });
  }

  @Test
  void leaveChat_Should_NotDeleteChat_When_OtherHumanMembersRemain() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getRcUserId()).thenReturn(RC_USER_ID);
    when(user.getMatrixUserId()).thenReturn(MATRIX_USER_ID);
    when(groupChatMembershipService.hasRemainingHumanMembers(ACTIVE_CHAT, MATRIX_USER_ID))
        .thenReturn(true);

    joinAndLeaveChatFacade.leaveChat(ACTIVE_CHAT.getId(), authenticatedUser);

    verify(rocketChatService, never()).deleteGroupAsSystemUser(anyString());
    verify(chatService, never()).deleteChat(any());
    verify(matrixChatShutdownService, never()).shutdownRoom(any());
  }

  @Test
  void leaveChatShouldDeleteChatAndShutDownMatrixRoomWhenLastMemberLeft() {
    Chat chat =
        Chat.builder()
            .id(CHAT_ID)
            .topic("topic")
            .consultingTypeId(0)
            .initialStartDate(LocalDateTime.now())
            .startDate(LocalDateTime.now())
            .duration(30)
            .repetitive(false)
            .active(true)
            .groupId("groupId")
            .matrixRoomId("!room:matrix.local")
            .build();
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(chat));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getRcUserId()).thenReturn(RC_USER_ID);
    // Matrix-native: the "last member left" decision comes from GroupChatMembershipService, not
    // from
    // Rocket.Chat's member query (ADR-004, RC disabled by default).
    when(groupChatMembershipService.hasRemainingHumanMembers(eq(chat), any())).thenReturn(false);
    when(rocketChatService.deleteGroupAsSystemUser(chat.getGroupId())).thenReturn(true);

    joinAndLeaveChatFacade.leaveChat(CHAT_ID, authenticatedUser);

    verify(chatService).deleteChat(chat);
    verify(matrixChatShutdownService).shutdownRoom(chat);
  }

  @Test
  void leaveChat_Should_RemoveLeaverFromMatrixRoomAndUserChatRelation() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getRcUserId()).thenReturn(RC_USER_ID);
    when(user.getMatrixUserId()).thenReturn(MATRIX_USER_ID);
    when(groupChatMembershipService.hasRemainingHumanMembers(ACTIVE_CHAT, MATRIX_USER_ID))
        .thenReturn(true);

    joinAndLeaveChatFacade.leaveChat(ACTIVE_CHAT.getId(), authenticatedUser);

    verify(groupChatMembershipService, times(1))
        .removeLeavingMemberFromRoom(ACTIVE_CHAT, MATRIX_USER_ID);
    verify(chatService, times(1)).deleteUserChatRelation(ACTIVE_CHAT, user);
  }

  @Test
  void leaveChat_Should_UseMatrixIdentityWithoutRocketChatCredentials()
      throws RocketChatRemoveUserFromGroupException {
    Chat matrixChat = mock(Chat.class);
    when(matrixChat.isActive()).thenReturn(true);
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(matrixChat));
    when(groupChatMembershipService.resolveMatrixRoomId(matrixChat))
        .thenReturn("!room:matrix.oriso.org");
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getMatrixUserId()).thenReturn(MATRIX_USER_ID);
    when(groupChatMembershipService.hasRemainingHumanMembers(matrixChat, MATRIX_USER_ID))
        .thenReturn(true);

    joinAndLeaveChatFacade.leaveChat(CHAT_ID, authenticatedUser);

    verify(chatPermissionVerifier).verifyPermissionForChat(matrixChat);
    verify(groupChatMembershipService).removeLeavingMemberFromRoom(matrixChat, MATRIX_USER_ID);
    verify(chatService).deleteUserChatRelation(matrixChat, user);
    verify(rocketChatService, never()).removeUserFromGroup(any(), any());
  }

  @Test
  void leaveChat_Should_DeleteChat_When_NoHumanMembersRemain() {
    Chat singleChat = mock(Chat.class);
    when(singleChat.isActive()).thenReturn(true);
    when(singleChat.isRepetitive()).thenReturn(false);
    when(singleChat.getGroupId()).thenReturn("groupId");
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(singleChat));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getRcUserId()).thenReturn(RC_USER_ID);
    when(user.getMatrixUserId()).thenReturn(MATRIX_USER_ID);
    when(groupChatMembershipService.hasRemainingHumanMembers(singleChat, MATRIX_USER_ID))
        .thenReturn(false);
    when(rocketChatService.deleteGroupAsSystemUser("groupId")).thenReturn(true);

    joinAndLeaveChatFacade.leaveChat(CHAT_ID, authenticatedUser);

    verify(chatService, times(1)).deleteChat(singleChat);
  }

  @Test
  void leaveChat_Should_RecreateChat_When_NoHumanMembersRemainInRepetitiveChat() {
    Chat repetitiveChat = mock(Chat.class);
    when(repetitiveChat.isActive()).thenReturn(true);
    when(repetitiveChat.isRepetitive()).thenReturn(true);
    when(repetitiveChat.nextStart()).thenReturn(LocalDateTime.now().plusWeeks(1));
    when(repetitiveChat.getGroupId()).thenReturn("groupId");
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(repetitiveChat));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getRcUserId()).thenReturn(RC_USER_ID);
    when(user.getMatrixUserId()).thenReturn(MATRIX_USER_ID);
    when(groupChatMembershipService.hasRemainingHumanMembers(repetitiveChat, MATRIX_USER_ID))
        .thenReturn(false);
    when(rocketChatService.deleteGroupAsSystemUser("groupId")).thenReturn(true);
    when(chatReCreator.recreateMessengerChat(repetitiveChat)).thenReturn("newGroupId");

    joinAndLeaveChatFacade.leaveChat(CHAT_ID, authenticatedUser);

    verify(chatReCreator, times(1)).updateAsNextChat(repetitiveChat, "newGroupId");
    verify(chatService, never()).deleteChat(any());
  }

  @Test
  void leaveChat_Should_CompleteFiniteSeries_When_NoNextOccurrenceRemains() {
    Chat repetitiveChat = mock(Chat.class);
    when(repetitiveChat.isActive()).thenReturn(true);
    when(repetitiveChat.isRepetitive()).thenReturn(true);
    when(repetitiveChat.nextStart()).thenReturn(null);
    when(repetitiveChat.getGroupId()).thenReturn("groupId");
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(repetitiveChat));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getRcUserId()).thenReturn(RC_USER_ID);
    when(user.getMatrixUserId()).thenReturn(MATRIX_USER_ID);
    when(groupChatMembershipService.hasRemainingHumanMembers(repetitiveChat, MATRIX_USER_ID))
        .thenReturn(false);
    when(rocketChatService.deleteGroupAsSystemUser("groupId")).thenReturn(true);

    joinAndLeaveChatFacade.leaveChat(CHAT_ID, authenticatedUser);

    verify(chatReCreator, never()).recreateMessengerChat(any());
    verify(chatReCreator, never()).updateAsNextChat(any(), any());
    verify(matrixChatShutdownService).shutdownRoom(repetitiveChat);
    verify(repetitiveChat).setActive(false);
    verify(chatService).saveChat(repetitiveChat);
  }

  @Test
  void leaveChat_Should_NotUseRocketChatMemberQueryForTheDeleteDecision() throws Exception {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getRcUserId()).thenReturn(RC_USER_ID);
    when(user.getMatrixUserId()).thenReturn(MATRIX_USER_ID);
    when(groupChatMembershipService.hasRemainingHumanMembers(ACTIVE_CHAT, MATRIX_USER_ID))
        .thenReturn(true);

    joinAndLeaveChatFacade.leaveChat(ACTIVE_CHAT.getId(), authenticatedUser);

    verify(rocketChatService, never()).getStandardMembersOfGroup(anyString());
  }

  /**
   * Regression test for the Matrix-only environment (rocket-chat.enabled=false, the default): the
   * inert Rocket.Chat adapter reports an empty member list for every group. Before this fix, that
   * made every single leave look like "the last member left" and deleted the chat for everyone.
   */
  @Test
  void leaveChat_Should_NotDeleteChat_When_RocketChatIsDisabledAndHumanMembersRemain() {
    var disabledRocketChatService =
        new DisabledRocketChatService(
            mock(RocketChatCredentialsProvider.class),
            mock(RocketChatConfig.class),
            mock(RocketChatMapper.class));
    var facade =
        new JoinAndLeaveChatFacade(
            chatService,
            chatPermissionVerifier,
            consultantService,
            userService,
            disabledRocketChatService,
            chatReCreator,
            groupChatMembershipService,
            matrixChatShutdownService,
            groupChatRoleService);
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(user.getRcUserId()).thenReturn(RC_USER_ID);
    when(user.getMatrixUserId()).thenReturn(MATRIX_USER_ID);
    when(groupChatMembershipService.hasRemainingHumanMembers(ACTIVE_CHAT, MATRIX_USER_ID))
        .thenReturn(true);

    facade.leaveChat(ACTIVE_CHAT.getId(), authenticatedUser);

    verify(chatService, never()).deleteChat(any());
  }
}
