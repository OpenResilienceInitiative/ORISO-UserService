package de.caritas.cob.userservice.api.facade;

import static java.util.Objects.isNull;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

import de.caritas.cob.userservice.api.adapters.web.dto.ChatMemberResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatMembersResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Facade to encapsulate the steps for get the chat members. */
@Service
@RequiredArgsConstructor
public class GetChatMembersFacade {

  private final @NonNull ChatService chatService;
  private final @NonNull ChatPermissionVerifier chatPermissionVerifier;
  private final @NonNull GroupChatMembershipService groupChatMembershipService;

  /**
   * Get a filtered list of the members of a chat (without technical/system user).
   *
   * <p>Members come from the Matrix room state (the only chat backend since Rocket.Chat was
   * disabled, ADR-004). Each Matrix member is mapped back to its application account so the UI
   * keeps showing usernames and display names. The response shape is unchanged.
   *
   * @param chatId chat ID
   * @return {@link ChatMembersResponseDTO}
   */
  public ChatMembersResponseDTO getChatMembers(Long chatId) {

    Chat chat =
        chatService
            .getChat(chatId)
            .orElseThrow(() -> new NotFoundException("Chat with id %s not found", chatId));

    verifyActiveStatus(chat);
    this.chatPermissionVerifier.verifyPermissionForChat(chat);
    verifyRocketChatGroup(chat);

    var matrixRoomId = groupChatMembershipService.resolveMatrixRoomId(chat);
    return convertResolvedMembersToChatMemberResponseDTO(
        groupChatMembershipService.resolveHumanMembers(matrixRoomId));
  }

  private void verifyActiveStatus(Chat chat) {
    if (isFalse(chat.isActive())) {
      throw new ConflictException(
          String.format(
              "Could not get members of chat with id %s, because it's not started.", chat.getId()));
    }
  }

  private void verifyRocketChatGroup(Chat chat) {
    if (isNull(chat.getGroupId())) {
      throw new InternalServerErrorException(
          String.format("Chat with id %s has no chat group id", chat.getId()));
    }
  }

  private ChatMembersResponseDTO convertResolvedMembersToChatMemberResponseDTO(
      List<ResolvedRoomMember> members) {
    var transcoder = new UsernameTranscoder();
    return new ChatMembersResponseDTO()
        .members(
            members.stream()
                .map(
                    member ->
                        new ChatMemberResponseDTO()
                            .id(member.matrixUserId())
                            .userId(member.accountId())
                            .status(null)
                            .username(transcoder.decodeUsername(member.username()))
                            .displayName(member.displayName())
                            .utcOffset(null))
                .collect(Collectors.toList()));
  }
}
