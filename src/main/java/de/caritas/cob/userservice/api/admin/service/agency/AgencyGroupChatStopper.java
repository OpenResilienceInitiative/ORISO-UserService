package de.caritas.cob.userservice.api.admin.service.agency;

import de.caritas.cob.userservice.api.actions.chat.StopChatActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.service.chat.RecurringGroupStopCoordinator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stops the active group chats of a Beratungsstelle that is being deleted (ORISO-Admin#1524), the
 * way the nightly stale-chat sweep stops a chat: each in its own transaction, so one chat whose
 * Matrix room cannot be shut down stays active and does not undo the others.
 *
 * <p>A recurring chat only loses its current occurrence; ending the series is not possible yet.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgencyGroupChatStopper {

  /** How many chats were stopped, and the ids of those that could not be. */
  public record Stopped(int count, List<Long> failedChatIds) {}

  private final @NonNull ChatRepository chatRepository;
  private final @NonNull ActionsRegistry actionsRegistry;
  private final @NonNull RecurringGroupStopCoordinator recurringStops;
  private final @NonNull PlatformTransactionManager transactionManager;
  private final @NonNull Clock clock;

  public long countActiveChatsOf(Long agencyId) {
    return chatRepository.findByAgencyIds(Set.of(agencyId)).stream().filter(Chat::isActive).count();
  }

  public Stopped stopActiveChatsOf(Long agencyId) {
    var transaction = new TransactionTemplate(transactionManager);
    var failed = new ArrayList<Long>();
    int stopped = 0;
    for (Chat candidate : chatRepository.findByAgencyIds(Set.of(agencyId))) {
      if (!candidate.isActive()) {
        continue;
      }
      try {
        if (stop(transaction, candidate.getId())) {
          stopped++;
        }
      } catch (RuntimeException e) {
        log.warn(
            "Could not stop group chat {} of deleted agency {} ({})",
            candidate.getId(),
            agencyId,
            e.getClass().getSimpleName());
        failed.add(candidate.getId());
      }
    }
    return new Stopped(stopped, failed);
  }

  private boolean stop(TransactionTemplate transaction, Long chatId) {
    var observed = transaction.execute(status -> chatRepository.findById(chatId).orElse(null));
    if (observed == null || !observed.isActive()) {
      return false;
    }
    if (RecurringGroupStopCoordinator.applies(observed)) {
      recurringStops.stop(observed, null, LocalDateTime.now(clock));
      return true;
    }
    return Boolean.TRUE.equals(
        transaction.execute(
            status ->
                chatRepository
                    .findByIdForUpdate(chatId)
                    // Another replica or the owner may have stopped it since it was read.
                    .filter(Chat::isActive)
                    .map(
                        chat -> {
                          actionsRegistry
                              .buildContainerForType(Chat.class)
                              .addActionToExecute(StopChatActionCommand.class)
                              .executeActions(chat);
                          return true;
                        })
                    .orElse(false)));
  }
}
