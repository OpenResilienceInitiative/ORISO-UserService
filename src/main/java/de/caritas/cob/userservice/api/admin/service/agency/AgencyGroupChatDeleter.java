package de.caritas.cob.userservice.api.admin.service.agency;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.chat.GroupChatMatrixCleanupService;
import de.caritas.cob.userservice.api.service.matrixgroup.ChatMatrixRoomHistory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the group chats of a Beratungsstelle that is being deleted (ORISO-Admin#1524) for good:
 * scheduled chats that have not started yet, running ones, and whole recurring series. Unlike a
 * stop, nothing is carried on: no next occurrence and no next Matrix room is created.
 *
 * <p>Each chat goes in its own transaction, under the owner and chat locks every other group writer
 * takes. Its records are deleted first; then its current room and the room already prepared for its
 * next occurrence are purged strictly. A room that cannot be purged rolls the deletion back, so the
 * chat is reported, kept, and deleted again on the next call. A room Synapse no longer knows counts
 * as purged, so that next call succeeds after a half-finished one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgencyGroupChatDeleter {

  /** How many chats were deleted, and the ids of those that could not be. */
  public record Deleted(int count, List<Long> failedChatIds) {}

  private final @NonNull ChatRepository chatRepository;
  private final @NonNull ChatService chatService;
  private final @NonNull ChatMatrixRoomHistory roomHistory;
  private final @NonNull GroupChatMatrixCleanupService matrixCleanup;
  private final @NonNull PlatformTransactionManager transactionManager;

  public long countChatsOf(Long agencyId) {
    return chatRepository.findByAgencyIds(Set.of(agencyId)).size();
  }

  public Deleted deleteChatsOf(Long agencyId) {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    var failed = new ArrayList<Long>();
    int deleted = 0;
    for (Chat candidate : chatRepository.findByAgencyIds(Set.of(agencyId))) {
      try {
        if (Boolean.TRUE.equals(transaction.execute(status -> delete(candidate)))) {
          deleted++;
        }
      } catch (RuntimeException e) {
        log.warn(
            "Could not delete group chat {} of deleted agency {} ({})",
            candidate.getId(),
            agencyId,
            e.getClass().getSimpleName());
        failed.add(candidate.getId());
      }
    }
    return new Deleted(deleted, failed);
  }

  private boolean delete(Chat candidate) {
    matrixCleanup.lockOwner(candidate.getChatOwner());
    var locked = chatRepository.findByIdForUpdate(candidate.getId());
    if (locked.isEmpty()) {
      return false; // Deleted since it was listed.
    }
    var chat = locked.get();
    // Read before the records go: the prepared room is only known through the room history.
    var rooms = new LinkedHashSet<String>();
    if (StringUtils.isNotBlank(chat.getMatrixRoomId())) {
      rooms.add(chat.getMatrixRoomId());
    }
    roomHistory.preparedNextRoom(chat).ifPresent(rooms::add);
    chatService.deleteChatRecords(chat);
    // Last, inside this transaction: a room that stays open rolls the deletion back.
    rooms.forEach(room -> purgeStrictly(chat.getId(), room));
    return true;
  }

  private void purgeStrictly(Long chatId, String room) {
    if (!matrixCleanup.purgeUnjournaledRoom(room)) {
      throw new RoomNotPurgedException(chatId);
    }
  }

  /** A room of the chat may still be open; the chat is kept for the next call. */
  static class RoomNotPurgedException extends RuntimeException {
    RoomNotPurgedException(Long chatId) {
      super("A Matrix room of group chat " + chatId + " could not be purged");
    }
  }
}
