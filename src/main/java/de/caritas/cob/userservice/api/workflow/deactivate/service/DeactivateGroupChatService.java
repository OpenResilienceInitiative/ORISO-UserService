package de.caritas.cob.userservice.api.workflow.deactivate.service;

import de.caritas.cob.userservice.api.actions.chat.StopChatActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Service to trigger stopping of group chats. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeactivateGroupChatService {

  private final @NonNull ChatRepository chatRepository;
  private final @NonNull ActionsRegistry actionsRegistry;
  private final @NonNull PlatformTransactionManager transactionManager;

  @Value("${group.chat.deactivateworkflow.periodMinutes}")
  private long deactivatePeriodMinutes;

  /**
   * Stops all still open group chats with special constraints.
   *
   * <p>Each chat is stopped in its own transaction (ORISO-UserService#1524). If a room cannot be
   * shut down, only that chat rolls back: it stays active and the next run retries it, while every
   * other chat of the run is stopped and committed. Re-locking the chat before the stop keeps the
   * stop exactly-once across replicas: a second replica waits for the lock and then finds the chat
   * already stopped.
   */
  public void deactivateStaleGroupChats() {
    var deactivationTime = LocalDateTime.now().minusMinutes(deactivatePeriodMinutes);
    var transaction = new TransactionTemplate(transactionManager);
    List<Long> candidates =
        transaction.execute(
            status ->
                this.chatRepository.findAllByActiveIsTrue().stream()
                    .filter(isStale(deactivationTime))
                    .map(Chat::getId)
                    .toList());
    if (candidates == null) {
      return;
    }
    candidates.forEach(chatId -> deactivateStaleActiveChat(transaction, chatId, deactivationTime));
  }

  private Predicate<Chat> isStale(LocalDateTime deactivationTime) {
    return chat ->
        isDeactivatable().test(chat)
            && isChatOutsideOfDeactivationTime(deactivationTime).test(chat);
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

  private void deactivateStaleActiveChat(
      TransactionTemplate transaction, Long chatId, LocalDateTime deactivationTime) {
    try {
      transaction.executeWithoutResult(
          status ->
              this.chatRepository
                  .findByIdForUpdate(chatId)
                  // Another replica may have stopped or rescheduled it since the candidates were
                  // read.
                  .filter(Chat::isActive)
                  .filter(isStale(deactivationTime))
                  .ifPresent(
                      chat ->
                          this.actionsRegistry
                              .buildContainerForType(Chat.class)
                              .addActionToExecute(StopChatActionCommand.class)
                              .executeActions(chat)));
    } catch (RuntimeException e) {
      // Only this chat rolled back: it stays active and the next run retries it.
      log.warn(
          "Could not stop stale group chat {}; it stays active for the next run ({})",
          chatId,
          e.getClass().getSimpleName());
    }
  }
}
