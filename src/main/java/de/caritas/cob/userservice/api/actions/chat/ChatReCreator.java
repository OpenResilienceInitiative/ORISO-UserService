package de.caritas.cob.userservice.api.actions.chat;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static java.util.Objects.isNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Re-creates the messenger room of a repetitive group chat for its next occurrence (ADR-004:
 * Matrix-native, Rocket.Chat is disabled).
 *
 * <p>The order of operations is fail-safe: the new Matrix room is created first, and only after
 * that succeeded the old room is shut down (best-effort, via {@link MatrixChatShutdownService}) so
 * its members receive the membership lifecycle signal. There is no lazy room provisioning for group
 * chats ({@code StartChatFacade} only activates a chat whose room already exists), so a failed
 * re-creation surfaces as an error while the chat stays untouched and usable — stopping the chat
 * again retries the whole operation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatReCreator {

  private static final String ROOM_ALIAS_PREFIX = "group_chat_";

  private final ChatService chatService;
  private final MatrixSynapseService matrixSynapseService;
  private final MatrixChatShutdownService matrixChatShutdownService;
  private final GroupChatMembershipService groupChatMembershipService;

  /**
   * Resets the given chat to its next occurrence: the new Matrix room id is persisted both as
   * legacy group id and as Matrix room id (mirroring chat creation) and the chat is deactivated
   * until it is started again.
   *
   * @param chat the repetitive {@link Chat} to update
   * @param matrixRoomId the id of the freshly created Matrix room
   */
  public void updateAsNextChat(Chat chat, String matrixRoomId) {
    var nextStart = chat.nextStart();
    chat.setGroupId(matrixRoomId);
    chat.setMatrixRoomId(matrixRoomId);
    chat.setStartDate(nextStart);
    chat.setCurrentOccurrenceIndex(chat.getCurrentOccurrenceIndex() + 1);
    chat.setUpdateDate(nowInUtc());
    chat.setActive(false);

    chatService.saveChat(chat);
  }

  /**
   * Creates a fresh Matrix room for the next occurrence of the given repetitive chat and shuts down
   * the old room afterwards (best-effort) so its members are removed and their clients can show the
   * chat as stopped.
   *
   * @param chat the repetitive {@link Chat} being stopped
   * @return the id of the newly created Matrix room
   */
  public String recreateMessengerChat(Chat chat) {
    var oldRoomId = groupChatMembershipService.resolveMatrixRoomId(chat);
    var humanMembers = groupChatMembershipService.resolveHumanMembers(oldRoomId);
    if (humanMembers.isEmpty()) {
      throw new InternalServerErrorException(
          String.format(
              "Could not resolve members of repetitive group chat %s before re-creating room",
              chat.getId()));
    }

    var newRoomId = createMatrixRoom(chat);
    var ownerMatrixUserId = chat.getChatOwner().getMatrixUserId();
    for (var member : humanMembers) {
      if (Objects.equals(ownerMatrixUserId, member.matrixUserId())) {
        continue;
      }
      if (!groupChatMembershipService.addMemberToRoom(
          newRoomId, ownerMatrixUserId, member.matrixUserId())) {
        throw new InternalServerErrorException(
            String.format(
                "Could not carry member %s into next occurrence of group chat %s",
                member.matrixUserId(), chat.getId()));
      }
    }
    matrixChatShutdownService.shutdownRoom(chat);

    return newRoomId;
  }

  private String createMatrixRoom(Chat chat) {
    var chatOwner = chat.getChatOwner();
    if (isNull(chatOwner) || isBlank(chatOwner.getMatrixUserId())) {
      throw new InternalServerErrorException(
          String.format(
              "Chat owner of repetitive group chat %s has no Matrix user; cannot re-create room",
              chat.getId()));
    }

    var roomAlias = ROOM_ALIAS_PREFIX + chat.getId() + "_" + Instant.now().toEpochMilli();
    try {
      var response =
          matrixSynapseService.createRoomAsMatrixUser(
              chat.getTopic(), roomAlias, chatOwner.getMatrixUserId());
      var body = response.getBody();
      if (isNull(body) || isBlank(body.getRoomId())) {
        throw new InternalServerErrorException(
            String.format(
                "Matrix returned no room id while re-creating room for group chat %s",
                chat.getId()));
      }
      log.info(
          "Re-created Matrix room {} for next occurrence of repetitive group chat {}",
          body.getRoomId(),
          chat.getId());
      return body.getRoomId();
    } catch (MatrixCreateRoomException e) {
      throw new InternalServerErrorException(
          String.format(
              "Error while creating Matrix room for repetitive group chat %s: %s",
              chat.getId(), e.getMessage()));
    }
  }
}
