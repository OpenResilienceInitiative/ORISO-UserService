package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.actions.chat.ChatReCreator;
import de.caritas.cob.userservice.api.actions.chat.StopChatActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.facade.ChatConverter;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits the next room before locking and advancing a recurring self-help group. */
@Service
@RequiredArgsConstructor
public class RecurringGroupStopCoordinator {
  private final ChatRepository chats;
  private final GroupChatMatrixCleanupService cleanup;
  private final ChatReCreator reCreator;
  private final ActionsRegistry actions;
  private final GroupChatPermissionService permissions;
  private final GroupChatMembershipService members;
  private final PlatformTransactionManager transactions;

  public static boolean applies(Chat chat) {
    return chat.isRepetitive()
        && ChatConverter.conversationTypeOf(chat) == ConversationType.SELF_HELP
        && chat.nextStart() != null;
  }

  public void stop(Chat observed, Consultant consultant, LocalDateTime staleCutoff) {
    stop(observed, consultant, staleCutoff, null);
  }

  public void stopAfterLastLeave(Chat observed, String leavingMatrixUserId) {
    stop(observed, null, null, leavingMatrixUserId);
  }

  private void stop(
      Chat observed, Consultant consultant, LocalDateTime staleCutoff, String lastLeaver) {
    var tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    var prepared =
        tx.execute(
            status -> {
              cleanup.lockOwner(observed.getChatOwner());
              var locked =
                  chats
                      .findByIdForUpdate(observed.getId())
                      .orElseThrow(() -> new ConflictException("Group no longer exists"));
              validate(observed, locked, consultant, staleCutoff);
              if (lastLeaver != null && members.hasRemainingHumanMembers(locked, lastLeaver))
                return null;
              reCreator.prepareLockedNextRoom(locked);
              return snapshot(locked);
            });
    if (prepared == null) return;
    tx.executeWithoutResult(
        status -> {
          cleanup.lockOwner(observed.getChatOwner());
          var locked =
              chats
                  .findByIdForUpdate(observed.getId())
                  .orElseThrow(() -> new ConflictException("Group no longer exists"));
          if (!Objects.equals(prepared, snapshot(locked)))
            throw new ConflictException("Group changed before stopping");
          validate(observed, locked, consultant, staleCutoff);
          if (lastLeaver != null && members.hasRemainingHumanMembers(locked, lastLeaver)) return;
          actions
              .buildContainerForType(Chat.class)
              .addActionToExecute(StopChatActionCommand.class)
              .executeActions(locked);
        });
  }

  /** Serialize a leave against preparation and finalization, checking the observed occurrence. */
  public <T> T withLockedOccurrence(Chat observed, Function<Chat, T> work) {
    var tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx.execute(
        status -> {
          cleanup.lockOwner(observed.getChatOwner());
          var locked =
              chats
                  .findByIdForUpdate(observed.getId())
                  .orElseThrow(() -> new ConflictException("Group no longer exists"));
          if (!locked.isActive() || !Objects.equals(snapshot(observed), snapshot(locked)))
            throw new ConflictException("Group changed before leaving");
          return work.apply(locked);
        });
  }

  private void validate(
      Chat expected, Chat actual, Consultant consultant, LocalDateTime staleCutoff) {
    if (!actual.isActive()
        || !applies(actual)
        || !Objects.equals(snapshot(expected), snapshot(actual)))
      throw new ConflictException("Group changed before stopping");
    if (consultant != null) permissions.requireCanModerate(actual, consultant);
    if (staleCutoff != null) {
      var start = actual.getStartDate() != null ? actual.getStartDate() : actual.getUpdateDate();
      if (start.plusMinutes(actual.getDuration()).isAfter(staleCutoff))
        throw new ConflictException("Group is no longer stale");
    }
  }

  private record Snapshot(
      Long id,
      String owner,
      Long tenant,
      String room,
      int occurrence,
      LocalDateTime start,
      LocalDateTime initial,
      LocalDateTime updated,
      int duration,
      int repeat,
      Chat.ChatInterval interval,
      boolean active) {}

  private static Snapshot snapshot(Chat chat) {
    return new Snapshot(
        chat.getId(),
        chat.getChatOwner().getId(),
        chat.getChatOwner().getTenantId(),
        chat.getMatrixRoomId(),
        chat.getCurrentOccurrenceIndex(),
        chat.getStartDate(),
        chat.getInitialStartDate(),
        chat.getUpdateDate(),
        chat.getDuration(),
        chat.getRepeatCount(),
        chat.getChatInterval(),
        chat.isActive());
  }
}
