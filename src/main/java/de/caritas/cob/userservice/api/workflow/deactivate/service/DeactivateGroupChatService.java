package de.caritas.cob.userservice.api.workflow.deactivate.service;

import de.caritas.cob.userservice.api.actions.chat.StopChatActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.function.Predicate;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Service to trigger stopping of group chats. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeactivateGroupChatService {

  private final @NonNull ChatRepository chatRepository;
  private final @NonNull ActionsRegistry actionsRegistry;

  @Value("${group.chat.deactivateworkflow.periodMinutes}")
  private long deactivatePeriodMinutes;

  /** Stops all still open group chats with special constraints. */
  @Transactional
  public void deactivateStaleGroupChats() {
    var deactivationTime = LocalDateTime.now().minusMinutes(deactivatePeriodMinutes);
    this.chatRepository.findAllByActiveIsTrue().stream()
        .filter(isDeactivatable())
        .filter(isChatOutsideOfDeactivationTime(deactivationTime))
        .forEach(this::deactivateStaleActiveChat);
  }

  /**
   * Internal team chats are persistent rooms for colleagues, not scheduled sessions that end.
   *
   * <p>They carry a duration like every other chat, so the sweep used to reach them once that
   * duration had passed. For a non-repetitive chat {@link StopChatActionCommand} deletes the chat
   * and shuts down its Matrix room, which destroyed the room and its history roughly an hour after
   * a counsellor first opened it - without any user action and with no way back. See
   * ORISO-UserService#984.
   */
  private Predicate<Chat> isDeactivatable() {
    return chat -> chat.getConversationType() != ConversationType.INTERNAL_GROUP;
  }

  private Predicate<Chat> isChatOutsideOfDeactivationTime(LocalDateTime deactivationTime) {
    return chat -> {
      var plannedStart = chat.getStartDate() != null ? chat.getStartDate() : chat.getUpdateDate();
      var plannedEnd = plannedStart.plusMinutes(chat.getDuration());
      return !plannedEnd.isAfter(deactivationTime);
    };
  }

  private void deactivateStaleActiveChat(Chat staleChat) {
    try {
      this.actionsRegistry
          .buildContainerForType(Chat.class)
          .addActionToExecute(StopChatActionCommand.class)
          .executeActions(staleChat);
    } catch (RuntimeException e) {
      // One stuck chat must not stop the sweep for all others. The final occurrence of a series
      // stays active and is retried next run; for a one-off chat this is the old best-effort end.
      log.warn(
          "Could not shut down the Matrix room of stale group chat {} ({})",
          staleChat.getId(),
          e.getClass().getSimpleName());
    }
  }
}
