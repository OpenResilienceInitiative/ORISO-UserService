package de.caritas.cob.userservice.api.facade;

import static java.util.Objects.isNull;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

import de.caritas.cob.userservice.api.adapters.web.dto.ChatMemberResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatMembersResponseDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
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
  private final @NonNull MembersOnlyChatAccess membersOnlyChatAccess;
  private final @NonNull AuthenticatedUser authenticatedUser;

  /**
   * Get a filtered list of the members of a chat (without technical/system user).
   *
   * <p>Members come from the Matrix room state. Each Matrix member is mapped back to its
   * application account so the UI keeps showing usernames, display names and each member's role
   * (OBP 2.1 #107: everyone in the chat sees everyone's role).
   *
   * <p>An advice seeker may read the list only while she is herself in the room: sharing the
   * Beratungsstelle opens a V1 group to join, not its member list.
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
    verifyMatrixRoom(chat);

    var matrixRoomId = groupChatMembershipService.resolveMatrixRoomId(chat);
    var members =
        membersOnlyChatAccess.readingAcrossTraeger(
            chat, () -> groupChatMembershipService.resolveHumanMembers(matrixRoomId));
    verifyAdviceSeekerIsMember(chat, members);
    return convertResolvedMembersToChatMemberResponseDTO(members);
  }

  private void verifyAdviceSeekerIsMember(Chat chat, List<ResolvedRoomMember> members) {
    var roles = authenticatedUser.getRoles();
    if (roles == null
        || !roles.contains(UserRole.USER.getValue())
        || roles.contains(UserRole.CONSULTANT.getValue())) {
      return;
    }
    var userId = authenticatedUser.getUserId();
    // Unknown room state yields no members: fail closed.
    var isMember =
        members.stream()
            .anyMatch(member -> !member.consultant() && member.accountId().equals(userId));
    if (!isMember) {
      throw new ForbiddenException(
          String.format(
              "User with id %s is not a member of chat with id %s", userId, chat.getId()));
    }
  }

  private void verifyActiveStatus(Chat chat) {
    if (isFalse(chat.isActive())) {
      throw new ConflictException(
          String.format(
              "Could not get members of chat with id %s, because it's not started.", chat.getId()));
    }
  }

  private void verifyMatrixRoom(Chat chat) {
    if (isNull(chat.getMatrixRoomId())) {
      throw new InternalServerErrorException(
          String.format("Chat with id %s has no Matrix room ID", chat.getId()));
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
                            .avatarKind(member.avatarKind())
                            .avatarId(member.avatarId())
                            .role(
                                member.consultant()
                                    ? ChatMemberResponseDTO.RoleEnum.CONSULTANT
                                    : ChatMemberResponseDTO.RoleEnum.ADVICE_SEEKER)
                            .utcOffset(null))
                .collect(Collectors.toList()));
  }
}
