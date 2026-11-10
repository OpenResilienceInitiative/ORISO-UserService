package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.ACTIVE_CHAT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CONSULTANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ChatInfoResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatInfoResponseDTO.CallerBanStateEnum;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class GetChatFacadeTest {

  @InjectMocks private GetChatFacade getChatFacade;

  @Mock private ChatService chatService;

  @Mock private ChatPermissionVerifier chatPermissionVerifier;

  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private UserService userService;
  @Mock private GroupChatMembershipService groupChatMembershipService;

  private static final String CALLER_MATRIX_ID = "@registered-asker:matrix.example";

  @Test
  public void getChat_Should_ThrowNotFoundException_WhenChatDoesNotExist() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.empty());

    try {
      getChatFacade.getChat(CHAT_ID, null);
      fail("Expected exception: NotFoundException");
    } catch (NotFoundException notFoundException) {
      assertTrue(true, "Excepted NotFoundException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
  }

  @Test
  public void getChat_Should_ThrowRequestForbiddenException_WhenConsultantHasNoPermissionForChat() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    doThrow(new ForbiddenException(""))
        .when(chatPermissionVerifier)
        .verifyPermissionForChat(ACTIVE_CHAT);

    try {
      getChatFacade.getChat(CHAT_ID, null);
      fail("Expected exception: RequestForbiddenException");
    } catch (ForbiddenException requestForbiddenException) {
      assertTrue(true, "Excepted RequestForbiddenException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
  }

  @Test
  public void getChat_Should_ThrowRequestForbiddenException_WhenUserHasNoPermissionForChat() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    doThrow(new ForbiddenException(""))
        .when(chatPermissionVerifier)
        .verifyPermissionForChat(ACTIVE_CHAT);

    try {
      getChatFacade.getChat(CHAT_ID, null);
      fail("Expected exception: RequestForbiddenException");
    } catch (ForbiddenException requestForbiddenException) {
      assertTrue(true, "Excepted RequestForbiddenException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
  }

  @Test
  public void getChat_Should_ReturnValidChatInfoResponseDTOForUser() {
    when(chatService.getChat(ACTIVE_CHAT.getId())).thenReturn(Optional.of(ACTIVE_CHAT));

    ChatInfoResponseDTO result = getChatFacade.getChat(ACTIVE_CHAT.getId(), null);

    assertThat(result, instanceOf(ChatInfoResponseDTO.class));
    assertEquals(ACTIVE_CHAT.getId(), result.getId());
    assertEquals(ACTIVE_CHAT.getMatrixRoomId(), result.getMatrixRoomId());
    assertEquals(true, result.getActive());

    verify(chatService, times(1)).getChat(ACTIVE_CHAT.getId());
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
  }

  @Test
  public void getChat_Should_ReturnValidChatInfoResponseDTOForConsultant() {
    when(chatService.getChat(ACTIVE_CHAT.getId())).thenReturn(Optional.of(ACTIVE_CHAT));
    when(chatPermissionVerifier.hasSameAgencyAssigned(ACTIVE_CHAT, CONSULTANT)).thenReturn(true);

    ChatInfoResponseDTO result = getChatFacade.getChat(ACTIVE_CHAT.getId(), null);

    assertThat(result, instanceOf(ChatInfoResponseDTO.class));
    assertEquals(ACTIVE_CHAT.getId(), result.getId());
    assertEquals(ACTIVE_CHAT.getMatrixRoomId(), result.getMatrixRoomId());
    assertEquals(true, result.getActive());

    verify(chatService, times(1)).getChat(ACTIVE_CHAT.getId());
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
  }

  @ParameterizedTest
  @EnumSource(CallerBanStateEnum.class)
  void optedInRegisteredAskerGetsOnlyTheirOwnRoomBanState(CallerBanStateEnum expected) {
    var chat = activeCircle();
    var user = registerAdviceSeeker(false);
    when(chatPermissionVerifier.hasChatUserAssignment(chat, user)).thenReturn(true);
    var membership =
        switch (expected) {
          case BANNED -> Optional.of(true);
          case NOT_BANNED -> Optional.of(false);
          case UNKNOWN -> Optional.<Boolean>empty();
        };
    when(groupChatMembershipService.isBannedFromRoom(chat, CALLER_MATRIX_ID))
        .thenReturn(membership);

    var response = getChatFacade.getChat(CHAT_ID, true);

    assertEquals(expected, response.getCallerBanState());
    assertTrue(response.getBannedUsers().isEmpty());
    verify(groupChatMembershipService).isBannedFromRoom(chat, CALLER_MATRIX_ID);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(booleans = false)
  void ordinaryPollNeverReadsMatrixBanState(Boolean includeCallerBan) {
    activeCircle();
    registerAdviceSeeker(false);

    assertNull(getChatFacade.getChat(CHAT_ID, includeCallerBan).getCallerBanState());
    verifyNoInteractions(userService, groupChatMembershipService);
  }

  @Test
  void deniedChatDoesNotReachOwnBanLookup() {
    var chat = activeCircle();
    registerAdviceSeeker(false);
    doThrow(new ForbiddenException("")).when(chatPermissionVerifier).verifyPermissionForChat(chat);

    assertThrows(ForbiddenException.class, () -> getChatFacade.getChat(CHAT_ID, true));
    verifyNoInteractions(userService, groupChatMembershipService);
  }

  @Test
  void sameAgencyAccessWithoutChatAssignmentDoesNotReadMatrixBanState() {
    var chat = activeCircle();
    var user = registerAdviceSeeker(false);
    when(chatPermissionVerifier.hasChatUserAssignment(chat, user)).thenReturn(false);
    when(groupChatMembershipService.isBannedFromRoom(chat, CALLER_MATRIX_ID))
        .thenReturn(Optional.of(true));

    assertNull(getChatFacade.getChat(CHAT_ID, true).getCallerBanState());
    verifyNoInteractions(groupChatMembershipService);
  }

  @Test
  void temporaryAccountDoesNotGetBanState() {
    activeCircle();
    registerAdviceSeeker(true);

    assertNull(getChatFacade.getChat(CHAT_ID, true).getCallerBanState());
    verifyNoInteractions(groupChatMembershipService);
  }

  @Test
  void missingRegisteredAccountDoesNotGetBanState() {
    activeCircle();
    when(authenticatedUser.isAdviceSeeker()).thenReturn(true);
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.empty());

    assertNull(getChatFacade.getChat(CHAT_ID, true).getCallerBanState());
    verifyNoInteractions(groupChatMembershipService);
  }

  @Test
  void anonymousAdviceSeekerDoesNotGetBanState() {
    activeCircle();
    registerAdviceSeeker(false);
    when(authenticatedUser.isAnonymous()).thenReturn(true);

    assertNull(getChatFacade.getChat(CHAT_ID, true).getCallerBanState());
    verifyNoInteractions(userService, groupChatMembershipService);
  }

  @Test
  void consultantDoesNotGetBanState() {
    activeCircle();
    when(authenticatedUser.isConsultant()).thenReturn(true);

    assertNull(getChatFacade.getChat(CHAT_ID, true).getCallerBanState());
    verifyNoInteractions(userService, groupChatMembershipService);
  }

  @Test
  void inactiveCircleDoesNotReadThePriorRoomBanState() {
    var chat = activeCircle();
    when(chat.isActive()).thenReturn(false);
    registerAdviceSeeker(false);

    assertNull(getChatFacade.getChat(CHAT_ID, true).getCallerBanState());
    verifyNoInteractions(userService, groupChatMembershipService);
  }

  @Test
  void internalGroupDoesNotGetBanState() {
    var chat = activeCircle();
    when(chat.getConversationType()).thenReturn(ConversationType.INTERNAL_GROUP);
    registerAdviceSeeker(false);

    assertNull(getChatFacade.getChat(CHAT_ID, true).getCallerBanState());
    verifyNoInteractions(userService, groupChatMembershipService);
  }

  private Chat activeCircle() {
    var chat = mock(Chat.class);
    when(chat.getId()).thenReturn(CHAT_ID);
    when(chat.getMatrixRoomId()).thenReturn("!current-circle:matrix.example");
    when(chat.isActive()).thenReturn(true);
    when(chat.getConversationType()).thenReturn(ConversationType.SELF_HELP);
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(chat));
    return chat;
  }

  private User registerAdviceSeeker(boolean temporaryAccount) {
    var user = mock(User.class);
    when(user.getMatrixUserId()).thenReturn(CALLER_MATRIX_ID);
    when(user.isTemporaryAccount()).thenReturn(temporaryAccount);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(true);
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    return user;
  }
}
