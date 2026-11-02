package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ORISO-UserService#1510: tells the counsellors @-mentioned in an ordinary conversation. The client
 * names them by the Matrix ids it put into {@code m.mentions}. That list is its claim, so only a
 * counsellor who is a member of the room right now gets a row: a forged id cannot reveal a
 * conversation to someone outside it. Best-effort: a failure never fails the message event.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageMentionNotificationService {

  private final @NonNull ConsultantRepository consultants;
  private final @NonNull MatrixSynapseService matrix;
  private final @NonNull AccountInactivityService accountLifecycle;
  private final @NonNull TeamDiscussionNotificationService conversationLevels;
  private final @NonNull EventNotificationService eventNotifications;

  public void notifyMentioned(
      String roomId,
      String senderUserId,
      List<String> mentionedMatrixUserIds,
      String threadRootId,
      PrivacyEnvelope envelope) {
    if (mentionedMatrixUserIds == null || mentionedMatrixUserIds.isEmpty() || envelope == null) {
      return;
    }
    try {
      var recipients = resolveRecipients(roomId, senderUserId, mentionedMatrixUserIds);
      if (!recipients.isEmpty()) {
        eventNotifications.createMentionNotifications(
            roomId, senderUserId, threadRootId, envelope, recipients);
      }
    } catch (RuntimeException ex) {
      log.warn("Could not mark the mentions of a message in room {}", roomId, ex);
    }
  }

  private Set<String> resolveRecipients(
      String roomId, String senderUserId, List<String> mentionedMatrixUserIds) {
    var members = matrix.getRoomMembers(roomId).orElse(List.of());
    Set<String> recipients = new HashSet<>();
    mentionedMatrixUserIds.stream()
        .filter(Objects::nonNull)
        .distinct()
        .filter(members::contains)
        .forEach(
            matrixUserId ->
                consultants
                    .findByMatrixUserIdAndDeleteDateIsNull(matrixUserId)
                    .map(Consultant::getId)
                    .ifPresent(recipients::add));
    recipients.remove(senderUserId);
    recipients.removeAll(accountLifecycle.inactiveAmong(recipients));
    recipients.removeIf(id -> conversationLevels.dropForConversationLevel(id, roomId, true));
    return recipients;
  }
}
