package de.caritas.cob.userservice.api.actions.chat;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static java.util.Objects.isNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.facade.ChatConverter;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.chat.GroupChatMatrixCleanupService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import de.caritas.cob.userservice.api.service.matrixgroup.ChatMatrixRoomHistory;
import de.caritas.cob.userservice.api.service.matrixgroup.MatrixGroupParticipationHistory;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-creates the Matrix room of a repetitive group chat for its next occurrence.
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
  private static final int MAX_RECONCILE_PASSES = 3;

  private final ChatService chatService;
  private final MatrixGroupParticipationHistory participationHistory;
  private final ChatMatrixRoomHistory roomHistory;
  private final MatrixSynapseService matrixSynapseService;
  private final MatrixChatShutdownService matrixChatShutdownService;
  private final GroupChatMembershipService groupChatMembershipService;
  private final SessionRepository sessionRepository;
  private final GroupChatMatrixCleanupService matrixCleanup;

  /**
   * Resets the given chat to its next occurrence: the new Matrix room id becomes the canonical room
   * reference and the chat remains inactive until its next scheduled start.
   *
   * @param chat the repetitive {@link Chat} to update
   * @param matrixRoomId the id of the freshly created Matrix room
   */
  @Transactional
  public void updateAsNextChat(Chat chat, String matrixRoomId) {
    roomHistory.recordRotation(chat, matrixRoomId);
    // The case list pairs a group's session and chat by this room id.
    sessionRepository
        .findByMatrixRoomId(chat.getMatrixRoomId())
        .ifPresent(
            session -> {
              session.setMatrixRoomId(matrixRoomId);
              sessionRepository.save(session);
            });
    var nextStart = chat.nextStart();
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
      // A stop that purged the old room and then failed in the database leaves the chat on a room
      // that no longer exists. Everyone was carried over before that purge: go on with their room.
      var carriedOver = roomHistory.preparedNextRoom(chat);
      if (carriedOver.isPresent()
          && roomHistory.membersWereCarried(chat, carriedOver.get())
          && matrixSynapseService.isRoomConfirmedGone(oldRoomId)) {
        return carriedOver.get();
      }
      throw new InternalServerErrorException(
          String.format(
              "Could not resolve members of repetitive group chat %s before re-creating room",
              chat.getId()));
    }

    var prepared = roomHistory.preparedNextRoom(chat);
    String newRoomId;
    if (prepared.isPresent()) {
      newRoomId = prepared.get();
    } else {
      var roomAlias = nextRoomAlias(chat);
      // Not every caller holds the group locks here, so only the alias is journaled: the worker
      // leaves it alone for a grace period and then purges the room unless it was adopted.
      var aliasIntent = matrixCleanup.recordAlias(ownerOf(chat), fullAlias(chat, roomAlias));
      var candidate = createMatrixRoom(chat, roomAlias);
      try {
        newRoomId = roomHistory.reserveNextRoom(chat, candidate);
      } catch (RuntimeException failure) {
        matrixSynapseService.purgeRoom(candidate);
        throw failure;
      }
      if (candidate.equals(newRoomId)) {
        matrixCleanup.discard(aliasIntent);
      } else {
        matrixSynapseService.purgeRoom(candidate);
      }
    }
    reconcileNextRoom(chat, oldRoomId, newRoomId, humanMembers);
    if (ChatConverter.conversationTypeOf(chat) == ConversationType.SELF_HELP) {
      roomHistory.markMembersCarried(
          chat.getId(),
          chat.getChatOwner().getTenantId(),
          chat.getCurrentOccurrenceIndex() + 1,
          newRoomId);
    }
    participationHistory.snapshotRoom(oldRoomId);
    matrixChatShutdownService.shutdownRoom(chat);

    return newRoomId;
  }

  /**
   * Makes the next room hold exactly the people of the current one. A departure or join that did
   * not take the group lock (a native Matrix client, or a leave after a failed transfer) is picked
   * up here: the current room is read again until it has stopped changing.
   */
  private void reconcileNextRoom(
      Chat chat, String oldRoomId, String newRoomId, List<ResolvedRoomMember> current) {
    var owner = chat.getChatOwner().getMatrixUserId();
    var carried = new HashSet<String>();
    for (int pass = 0; pass < MAX_RECONCILE_PASSES; pass++) {
      var expected =
          current.stream().map(ResolvedRoomMember::matrixUserId).collect(Collectors.toSet());
      for (var member : expected) {
        if (Objects.equals(owner, member) || carried.contains(member)) {
          continue;
        }
        if (!groupChatMembershipService.addMemberToRoom(newRoomId, owner, member)) {
          throw new InternalServerErrorException(
              String.format(
                  "Could not carry member %s into next occurrence of group chat %s",
                  member, chat.getId()));
        }
        carried.add(member);
      }
      // The owner created the next room, so an empty answer means it could not be read.
      var present = groupChatMembershipService.resolveHumanMembers(newRoomId);
      if (present.isEmpty()) {
        throw new InternalServerErrorException(
            String.format(
                "Could not read members of the next occurrence of group chat %s", chat.getId()));
      }
      for (var member : present) {
        var id = member.matrixUserId();
        if (Objects.equals(owner, id) || expected.contains(id)) {
          continue;
        }
        if (!groupChatMembershipService.removeMemberFromRoomAndConfirm(newRoomId, id)) {
          throw new InternalServerErrorException(
              String.format(
                  "Could not remove departed member %s from next occurrence of group chat %s",
                  id, chat.getId()));
        }
        carried.remove(id);
      }
      var latest = groupChatMembershipService.resolveHumanMembers(oldRoomId);
      if (latest.isEmpty()) {
        throw new InternalServerErrorException(
            String.format(
                "Could not resolve members of repetitive group chat %s before re-creating room",
                chat.getId()));
      }
      if (latest.stream()
          .map(ResolvedRoomMember::matrixUserId)
          .collect(Collectors.toSet())
          .equals(expected)) {
        return;
      }
      current = latest;
    }
    throw new InternalServerErrorException(
        String.format(
            "Members of group chat %s kept changing while preparing its next occurrence",
            chat.getId()));
  }

  /**
   * A leaver must not stay in a room that is already prepared for the next occurrence. Call before
   * the leave itself: when Matrix cannot confirm the removal nothing has changed yet, and the leave
   * can simply be repeated.
   */
  public void removeFromPreparedRoom(Chat chat, String matrixUserId) {
    if (chat.getChatOwner() != null
        && Objects.equals(chat.getChatOwner().getMatrixUserId(), matrixUserId)) {
      return; // The owner holds the prepared room; a departed owner is evicted at the transfer.
    }
    roomHistory
        .preparedNextRoom(chat)
        .ifPresent(
            room -> {
              if (!groupChatMembershipService.removeMemberFromRoomAndConfirm(room, matrixUserId)) {
                throw new CustomValidationHttpStatusException(
                    HttpStatusExceptionReason.INTERNAL_CHAT_MEMBERSHIP_FAILED,
                    HttpStatus.BAD_GATEWAY);
              }
            });
  }

  /** Prepare a future self-help room while the caller holds owner and chat locks. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void prepareLockedNextRoom(Chat chat) {
    if (roomHistory.preparedNextRoom(chat).isPresent()) return;
    var oldRoomId = groupChatMembershipService.resolveMatrixRoomId(chat);
    if (groupChatMembershipService.resolveHumanMembers(oldRoomId).isEmpty()) {
      throw new InternalServerErrorException(
          String.format(
              "Could not resolve members of repetitive group chat %s before re-creating room",
              chat.getId()));
    }
    var roomAlias = nextRoomAlias(chat);
    // Committed before the room exists: a process that dies right after creating it leaves an
    // intent the worker resolves through the alias.
    var aliasIntent = matrixCleanup.recordAlias(ownerOf(chat), fullAlias(chat, roomAlias));
    var candidate = createMatrixRoom(chat, roomAlias);
    Long taskId;
    try {
      taskId = matrixCleanup.bindRoom(aliasIntent, ownerOf(chat), candidate);
    } catch (RuntimeException failure) {
      matrixSynapseService.purgeRoom(candidate);
      throw failure;
    }
    // On rollback the independently committed intent remains; the worker waits for the owner
    // lock, then purges only if no room provenance was committed.
    roomHistory.recordRotation(chat, candidate);
    matrixCleanup.clear(taskId);
  }

  private GroupChatMatrixCleanupService.GroupOwner ownerOf(Chat chat) {
    return new GroupChatMatrixCleanupService.GroupOwner(
        chat.getId(), chat.getChatOwner().getId(), chat.getChatOwner().getTenantId());
  }

  private String nextRoomAlias(Chat chat) {
    var chatOwner = chat.getChatOwner();
    if (isNull(chatOwner) || isBlank(chatOwner.getMatrixUserId())) {
      throw new InternalServerErrorException(
          String.format(
              "Chat owner of repetitive group chat %s has no Matrix user; cannot re-create room",
              chat.getId()));
    }
    return ROOM_ALIAS_PREFIX + chat.getId() + "_" + Instant.now().toEpochMilli();
  }

  /** The room is created on the owner's homeserver, which is the server part of their user id. */
  private String fullAlias(Chat chat, String roomAlias) {
    var owner = chat.getChatOwner().getMatrixUserId();
    return "#" + roomAlias + ":" + owner.substring(owner.indexOf(':') + 1);
  }

  private String createMatrixRoom(Chat chat, String roomAlias) {
    var chatOwner = chat.getChatOwner();
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
