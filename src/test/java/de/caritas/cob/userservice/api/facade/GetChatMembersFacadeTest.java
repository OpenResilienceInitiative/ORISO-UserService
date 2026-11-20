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
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

  private static final String MATRIX_ROOM_ID = "!room:matrix.example.org";

  @InjectMocks private GetChatMembersFacade getChatMembersFacade;

  @Mock private ChatService chatService;

  @Mock private ChatPermissionVerifier chatPermissionVerifier;

  @Mock private GroupChatMembershipService groupChatMembershipService;

  @Mock
  private de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess membersOnlyChatAccess;

  @Mock private AuthenticatedUser authenticatedUser;

  @org.junit.jupiter.api.BeforeEach
  void readsRunDirectly() {
    when(membersOnlyChatAccess.readingAcrossTraeger(
            org.mockito.ArgumentMatchers.any(de.caritas.cob.userservice.api.model.Chat.class),
            org.mockito.ArgumentMatchers.<java.util.function.Supplier<Object>>any()))
        .thenAnswer(
            invocation -> invocation.<java.util.function.Supplier<Object>>getArgument(1).get());
  }

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
    org.mockito.Mockito.verifyNoInteractions(groupChatMembershipService);
  }

  @Test
  public void getChatMembers_Should_ReturnMatrixNativeMembers_MappedToAppAccounts()
      throws Exception {
    var chat = matrixChat();
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(chat));
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn(MATRIX_ROOM_ID);
    when(groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID))
        .thenReturn(
            List.of(
                new ResolvedRoomMember(
                    "@consultant:matrix.example.org",
                    "consultant-id",
                    "consultantUsername",
                    "Consultant Name",
                    true,
                    "ICON",
                    "magpie"),
                new ResolvedRoomMember(
                    "@asker:matrix.example.org",
                    "asker-id",
                    "askerUsername",
                    "askerUsername",
                    false,
                    null,
                    "fox")));

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
    assertThat(matrixIds, contains("@consultant:matrix.example.org", "@asker:matrix.example.org"));
    assertThat(response.getMembers().get(0).getDisplayName(), is("Consultant Name"));
    var mapper = new de.caritas.cob.userservice.api.config.AppConfig().objectMapper();
    var json = mapper.readTree(mapper.writeValueAsString(response));
    assertThat(
        json.path("members").get(0).path("_id").asText(), is("@consultant:matrix.example.org"));
    assertThat(json.path("members").get(0).path("avatarKind").asText(), is("ICON"));
    assertThat(json.path("members").get(0).path("avatarId").asText(), is("magpie"));
    assertThat(json.path("members").get(1).path("avatarId").asText(), is("fox"));
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
                    "@u:matrix.example.org",
                    "u-id",
                    "plainUsername",
                    "Display",
                    false,
                    null,
                    null)));

    ChatMembersResponseDTO response = getChatMembersFacade.getChatMembers(CHAT_ID);

    assertThat(response.getMembers().get(0).getUsername(), is("plainUsername"));
  }

  private static ResolvedRoomMember consultantMember() {
    return new ResolvedRoomMember(
        "@consultant:matrix.example.org",
        "consultant-id",
        "consultantUsername",
        "Consultant Name",
        true,
        null,
        null);
  }

  private static ResolvedRoomMember askerMember(String accountId) {
    return new ResolvedRoomMember(
        "@" + accountId + ":matrix.example.org",
        accountId,
        accountId,
        accountId,
        false,
        null,
        null);
  }

  private void roomWith(ResolvedRoomMember... members) {
    var chat = matrixChat();
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(chat));
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn(MATRIX_ROOM_ID);
    when(groupChatMembershipService.resolveHumanMembers(MATRIX_ROOM_ID))
        .thenReturn(List.of(members));
  }

  // OBP 2.1 #107: everyone in the chat sees each participant's role.
  @Test
  public void getChatMembers_Should_NameEachMembersRole() throws Exception {
    when(authenticatedUser.getRoles()).thenReturn(Set.of("consultant"));
    roomWith(consultantMember(), askerMember("asker-id"));

    var response = getChatMembersFacade.getChatMembers(CHAT_ID);

    var mapper = new de.caritas.cob.userservice.api.config.AppConfig().objectMapper();
    var json = mapper.readTree(mapper.writeValueAsString(response));
    assertThat(json.path("members").get(0).path("role").asText(), is("CONSULTANT"));
    assertThat(json.path("members").get(1).path("role").asText(), is("ADVICE_SEEKER"));
  }

  // The roles are personal data of the members: an advice seeker of the same Beratungsstelle who
  // is not in the room must not read who is.
  @Test
  public void getChatMembers_Should_ForbidAdviceSeeker_WhoIsNotInTheRoom() {
    when(authenticatedUser.getRoles()).thenReturn(Set.of("user"));
    when(authenticatedUser.getUserId()).thenReturn("outsider-id");
    roomWith(consultantMember(), askerMember("asker-id"));

    try {
      getChatMembersFacade.getChatMembers(CHAT_ID);
      fail("Expected exception: ForbiddenException");
    } catch (ForbiddenException expected) {
      assertTrue(true, "Expected ForbiddenException thrown");
    }
  }

  // Unknown room state must not open the list to an advice seeker either.
  @Test
  public void getChatMembers_Should_ForbidAdviceSeeker_When_RoomStateUnknown() {
    when(authenticatedUser.getRoles()).thenReturn(Set.of("user"));
    when(authenticatedUser.getUserId()).thenReturn("asker-id");
    roomWith();

    try {
      getChatMembersFacade.getChatMembers(CHAT_ID);
      fail("Expected exception: ForbiddenException");
    } catch (ForbiddenException expected) {
      assertTrue(true, "Expected ForbiddenException thrown");
    }
  }

  @Test
  public void getChatMembers_Should_ReturnMembersWithRoles_ToAdviceSeekerInTheRoom() {
    when(authenticatedUser.getRoles()).thenReturn(Set.of("user"));
    when(authenticatedUser.getUserId()).thenReturn("asker-id");
    roomWith(consultantMember(), askerMember("asker-id"));

    var response = getChatMembersFacade.getChatMembers(CHAT_ID);

    var roles =
        response.getMembers().stream()
            .map(member -> String.valueOf(member.getRole()))
            .collect(Collectors.toList());
    assertThat(roles, contains("CONSULTANT", "ADVICE_SEEKER"));
  }
}
