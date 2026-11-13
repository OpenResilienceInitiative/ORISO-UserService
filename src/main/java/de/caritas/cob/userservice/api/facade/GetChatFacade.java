package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.adapters.web.dto.ChatInfoResponseDTO.CallerBanStateEnum.BANNED;
import static de.caritas.cob.userservice.api.adapters.web.dto.ChatInfoResponseDTO.CallerBanStateEnum.NOT_BANNED;
import static de.caritas.cob.userservice.api.adapters.web.dto.ChatInfoResponseDTO.CallerBanStateEnum.UNKNOWN;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.adapters.web.dto.ChatInfoResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.user.UserService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Facade for capsuling to get a chat. */
@Service
@RequiredArgsConstructor
public class GetChatFacade {

  private final @NonNull ChatService chatService;
  private final @NonNull ChatPermissionVerifier chatPermissionVerifier;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull UserService userService;
  private final @NonNull GroupChatMembershipService groupChatMembershipService;

  /**
   * Get chat info.
   *
   * @param chatId chat ID
   * @param includeCallerBan whether the advice seeker requests their own ban state
   * @return {@link ChatInfoResponseDTO}
   */
  public ChatInfoResponseDTO getChat(Long chatId, Boolean includeCallerBan) {

    Chat chat =
        chatService
            .getChat(chatId)
            .orElseThrow(() -> new NotFoundException("Chat with id %s not found.", chatId));

    this.chatPermissionVerifier.verifyPermissionForChat(chat);

    var info =
        new ChatInfoResponseDTO()
            .id(chat.getId())
            .matrixRoomId(chat.getMatrixRoomId())
            .active(isTrue(chat.isActive()));

    if (isTrue(includeCallerBan)
        && chat.isActive()
        && ChatConverter.conversationTypeOf(chat) == ConversationType.SELF_HELP
        && authenticatedUser.isAdviceSeeker()
        && !authenticatedUser.isAnonymous()) {
      userService
          .getUserViaAuthenticatedUser(authenticatedUser)
          .filter(
              user ->
                  !user.isTemporaryAccount()
                      && chatPermissionVerifier.hasChatUserAssignment(chat, user))
          .ifPresent(
              user ->
                  info.setCallerBanState(
                      groupChatMembershipService
                          .isBannedFromRoom(chat, user.getMatrixUserId())
                          .map(banned -> banned ? BANNED : NOT_BANNED)
                          .orElse(UNKNOWN)));
    }
    return info;
  }
}
