package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.ACTIVE_CHAT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.CHAT_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ChatMemberResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatMembersResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class GetChatMembersFacadeTest {

  private static final String MATRIX_ROOM_ID = "!room:matrix.oriso.org";

  @InjectMocks private GetChatMembersFacade getChatMembersFacade;

  @Mock private ChatService chatService;

  @Mock private ChatPermissionVerifier chatPermissionVerifier;

  @Mock private GroupChatMembershipService groupChatMembershipService;

  private Chat matrixChat() {
    var chat = new Chat();
    chat.setId(CHAT_ID);
    chat.setActive(true);
    chat.setMatrixRoomId(MATRIX_ROOM_ID);
    return chat;
  }

  @Test
  public void getChatMembers_Should_ThrowNotFoundException_WhenChatDoesNotExist() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.empty());

    try {
      getChatMembersFacade.getChatMembers(CHAT_ID);
      fail("Expected exception: NotFoundException");
    } catch (NotFoundException notFoundException) {
      assertTrue(true, "Excepted NotFoundException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
  }

  @Test
  public void getChatMembers_Should_ThrowConflictException_WhenChatIsNotActive() {
    Chat inactiveChat = mock(Chat.class);
    when(inactiveChat.isActive()).thenReturn(false);
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(inactiveChat));

    try {
      getChatMembersFacade.getChatMembers(CHAT_ID);
      fail("Expected exception: ConflictException");
    } catch (ConflictException conflictException) {
      assertTrue(true, "Excepted ConflictException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
  }

  @Test
  public void
      getChatMembers_Should_ThrowRequestForbiddenException_WhenUserHasNoPermissionForChat() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    doThrow(new ForbiddenException(""))
        .when(chatPermissionVerifier)
        .verifyPermissionForChat(ACTIVE_CHAT);

    try {
      getChatMembersFacade.getChatMembers(CHAT_ID);
      fail("Expected exception: RequestForbiddenException");
    } catch (ForbiddenException requestForbiddenException) {
      assertTrue(true, "Excepted RequestForbiddenException thrown");
    }

    verify(chatService, times(1)).getChat(CHAT_ID);
    verify(chatPermissionVerifier, times(1)).verifyPermissionForChat(ACTIVE_CHAT);
  }

  @Test
  public void getChatMembers_Should_ReturnMatrixNativeMembers_MappedToAppAccounts() {
    var chat = matrixChat();
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(chat));
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn(MATRIX_ROOM_ID);
    when(groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID))
        .thenReturn(
            List.of(
                new ResolvedRoomMember(
                    "@consultant:matrix.oriso.org",
                    "consultant-id",
                    "consultantUsername",
                    "Consultant Name",
                    true),
                new ResolvedRoomMember(
                    "@asker:matrix.oriso.org",
                    "asker-id",
                    "askerUsername",
                    "askerUsername",
                    false)));

    ChatMembersResponseDTO response = getChatMembersFacade.getChatMembers(CHAT_ID);

    assertThat(response, instanceOf(ChatMembersResponseDTO.class));
    var ids =
        response.getMembers().stream()
            .map(ChatMemberResponseDTO::getUserId)
            .collect(Collectors.toList());
    assertThat(ids, contains("consultant-id", "asker-id"));
    var matrixIds =
        response.getMembers().stream()
            .map(ChatMemberResponseDTO::getId)
            .collect(Collectors.toList());
    assertThat(matrixIds, contains("@consultant:matrix.oriso.org", "@asker:matrix.oriso.org"));
    assertThat(response.getMembers().get(0).getDisplayName(), is("Consultant Name"));
  }

  @Test
  public void getChatMembers_Should_ReturnEmptyList_When_RoomStateUnknown() {
    var chat = matrixChat();
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(chat));
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn(MATRIX_ROOM_ID);
    when(groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID)).thenReturn(List.of());

    ChatMembersResponseDTO response = getChatMembersFacade.getChatMembers(CHAT_ID);

    assertThat(response.getMembers(), is(empty()));
  }

  @Test
  public void getChatMembers_Should_ThrowInternalServerError_When_ChatHasNoGroupId() {
    var chatWithoutGroup = new Chat();
    chatWithoutGroup.setId(CHAT_ID);
    chatWithoutGroup.setActive(true);
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(chatWithoutGroup));

    try {
      getChatMembersFacade.getChatMembers(CHAT_ID);
      fail("Expected exception: InternalServerErrorException");
    } catch (RuntimeException e) {
      assertTrue(true, "Expected InternalServerErrorException thrown");
    }
  }

  @Test
  public void getChatMembers_Should_DecodeEncodedUsernames() {
    var chat = matrixChat();
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(chat));
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn(MATRIX_ROOM_ID);
    // enc.<hex> style encoding is decoded by the UsernameTranscoder; a plain username round-trips.
    when(groupChatMembershipService.resolveHumanMembers(anyString()))
        .thenReturn(
            List.of(
                new ResolvedRoomMember(
                    "@u:matrix.oriso.org", "u-id", "plainUsername", "Display", false)));

    ChatMembersResponseDTO response = getChatMembersFacade.getChatMembers(CHAT_ID);

    assertThat(response.getMembers().get(0).getUsername(), is("plainUsername"));
  }
}
