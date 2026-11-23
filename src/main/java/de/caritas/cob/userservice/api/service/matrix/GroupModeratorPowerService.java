package de.caritas.cob.userservice.api.service.matrix;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps a group's Matrix power levels in line with its roles (ORISO-Frontend#2229). Moderators of a
 * group are its owners and its named co-moderators (product owner, 12 Nov 2026); they hold at least
 * the Matrix moderator level (50), which lets them blur passages ({@code org.oriso.passage_blur})
 * and lift any blur ({@code redact}). A participant, or someone who left, holds no moderator level.
 * The room creator keeps 100: nobody above the moderator level is ever lowered.
 *
 * <p>Call after every role transition: creation, invitation, admission, role change, ownership
 * transfer, removal and leave. Raising is best effort and reported: a missing level only withholds
 * blurring. Taking the level back is strict ({@link #reconcileOrFail}) for a role change, a
 * removal, a leave, a deselection, and an admission as a participant, so a former moderator never
 * keeps the level.
 *
 * <p>Logs no Matrix user ID (KDG).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupModeratorPowerService {

  private final MatrixSynapseService matrixSynapseService;
  private final GroupChatParticipantRepository participantRepository;
  private final ConsultantRepository consultantRepository;
  private final PlatformTransactionManager transactions;

  /** Owners and co-moderators moderate a group; participants and leavers ({@code null}) do not. */
  public static boolean isModerator(ParticipantRole role) {
    return role == ParticipantRole.OWNER || role == ParticipantRole.CO_MODERATOR;
  }

  /**
   * Brings one member's Matrix level in line with their role in the group.
   *
   * @param role the member's role now, {@code null} when they are no longer a participant
   * @return true when the room holds the right level afterwards, or there is nothing to do
   */
  public boolean reconcile(Chat chat, String matrixUserId, ParticipantRole role) {
    var roomId = roomIdOf(chat);
    if (roomId == null || isBlank(matrixUserId)) {
      return true;
    }
    var done =
        matrixSynapseService.syncModeratorPower(
            roomId, Map.of(matrixUserId, isModerator(role)), ownerOf(chat));
    if (!done) {
      log.warn(
          "The Matrix moderator level of a member of group chat {} could not be brought in line"
              + " with their role",
          chat.getId());
    }
    return done;
  }

  /**
   * Like {@link #reconcile}, but answers {@code 502 Bad Gateway} when Matrix cannot confirm it.
   * Call inside the transaction of a demotion, so the role change rolls back and can be repeated.
   */
  public void reconcileOrFail(Chat chat, String matrixUserId, ParticipantRole role) {
    if (!reconcile(chat, matrixUserId, role)) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.INTERNAL_CHAT_MEMBERSHIP_FAILED, HttpStatus.BAD_GATEWAY);
    }
  }

  /**
   * Raises the moderators selected when a group is created, in one write, before the {@link Chat}
   * may know its room. Selected self-help moderators are only invited after the commit; the level
   * written now holds once they join.
   *
   * @return true when every moderator holds the level afterwards
   */
  public boolean grantModerators(
      String roomId, String holderMatrixUserId, Collection<String> moderatorMatrixUserIds) {
    var moderators = new LinkedHashMap<String, Boolean>();
    moderatorMatrixUserIds.stream()
        .filter(id -> !isBlank(id))
        .forEach(id -> moderators.put(id, true));
    if (moderators.isEmpty() || !MatrixIds.isRoomId(roomId)) {
      return true;
    }
    var done = matrixSynapseService.syncModeratorPower(roomId, moderators, holderMatrixUserId);
    if (!done) {
      log.warn("The moderators of new group room {} could not be raised", roomId);
    }
    return done;
  }

  /**
   * Brings the moderators of the group in {@code roomId}, the room of its next occurrence, in line
   * with their roles. The room starts with its creator as its only moderator; a room prepared by an
   * earlier stop that failed may still hold the level of someone who stopped moderating since, and
   * that level is taken back here, before the room becomes the group's current one.
   *
   * <p>Raising is best effort. Taking a level back is strict: when Matrix cannot confirm it, this
   * answers {@code 502 Bad Gateway}, so the stop fails and is repeated with the same room.
   *
   * @return true when every moderator holds the level afterwards
   */
  public boolean grantModeratorsOf(Chat chat, String roomId) {
    if (!MatrixIds.isRoomId(roomId)) {
      return true;
    }
    var moderatorIds = moderatorMatrixUserIds(chat.getId());
    // Unknown is not empty: a room whose levels cannot be read must not become current unchecked.
    var holders =
        matrixSynapseService
            .moderatorLevelHolders(roomId)
            .orElseThrow(
                () -> {
                  log.warn(
                      "The moderators of the next room of group chat {} could not be read",
                      chat.getId());
                  return new CustomValidationHttpStatusException(
                      HttpStatusExceptionReason.INTERNAL_CHAT_MEMBERSHIP_FAILED,
                      HttpStatus.BAD_GATEWAY);
                });
    var moderators = new LinkedHashMap<String, Boolean>();
    holders.stream()
        .filter(id -> !moderatorIds.contains(id))
        .forEach(id -> moderators.put(id, false));
    var revokes = !moderators.isEmpty();
    moderatorIds.forEach(id -> moderators.put(id, true));
    if (moderators.isEmpty()) {
      return true;
    }
    var done = matrixSynapseService.syncModeratorPower(roomId, moderators, ownerOf(chat));
    if (!done && revokes) {
      log.warn(
          "A former moderator of group chat {} still holds the level in its next room",
          chat.getId());
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.INTERNAL_CHAT_MEMBERSHIP_FAILED, HttpStatus.BAD_GATEWAY);
    }
    if (!done) {
      log.warn(
          "The moderators of the next room of group chat {} could not be raised", chat.getId());
    }
    return done;
  }

  /**
   * Raises {@code matrixUserIds} to the moderator level once the current transaction has committed,
   * and only those who are still moderators of the group then. A promotion that rolls back
   * therefore never leaves a Matrix moderator level behind. Runs at once outside a transaction.
   * Best effort, like {@link #reconcile}: a failure is logged and only withholds blurring.
   */
  public void grantModeratorsAfterCommit(Chat chat, Collection<String> matrixUserIds) {
    var roomId = roomIdOf(chat);
    if (roomId == null || matrixUserIds == null) {
      return;
    }
    var requested = matrixUserIds.stream().filter(id -> !isBlank(id)).distinct().toList();
    if (requested.isEmpty()) {
      return;
    }
    var seriesId = chat.getId();
    var holder = ownerOf(chat);
    Runnable raise = () -> grantCurrentModerators(seriesId, roomId, holder, requested);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              raise.run();
            }
          });
    } else {
      raise.run();
    }
  }

  /**
   * Raises those of {@code requested} who moderate the group right now, while holding the group's
   * participant rows locked: the same rows a demotion, removal, leave or deselection locks before
   * it takes a level back. A raise therefore never reads a role that a concurrent demotion is about
   * to end, and never writes after that demotion took the level back. The lock is a database lock,
   * so it holds across service instances. Best effort: a failure is logged and only withholds
   * blurring.
   */
  public void grantCurrentModerators(
      Long seriesId, String roomId, String holder, Collection<String> requested) {
    if (seriesId == null || !MatrixIds.isRoomId(roomId) || requested == null) {
      return;
    }
    var wanted = requested.stream().filter(id -> !isBlank(id)).distinct().toList();
    if (wanted.isEmpty()) {
      return;
    }
    try {
      var template = new TransactionTemplate(transactions);
      template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      template.executeWithoutResult(
          status -> {
            var current = lockedModeratorMatrixUserIds(seriesId);
            grantModerators(roomId, holder, wanted.stream().filter(current::contains).toList());
          });
    } catch (RuntimeException failure) {
      log.warn(
          "The moderators of group chat {} could not be raised ({})",
          seriesId,
          failure.getClass().getSimpleName());
    }
  }

  private List<String> lockedModeratorMatrixUserIds(Long seriesId) {
    return moderatorMatrixUserIds(
        TenantContext.supplyAcrossTenants(
            () -> participantRepository.findBySeriesIdForUpdate(seriesId)));
  }

  /** Matrix user IDs of the group's owners and co-moderators, in one consultant lookup. */
  private List<String> moderatorMatrixUserIds(Long seriesId) {
    return moderatorMatrixUserIds(
        TenantContext.supplyAcrossTenants(() -> participantRepository.findBySeriesId(seriesId)));
  }

  private List<String> moderatorMatrixUserIds(List<GroupChatParticipant> participations) {
    // A co-moderator may come from another Träger (recipient scope PLATFORM): resolved by
    // participation, across Träger.
    return TenantContext.supplyAcrossTenants(
        () -> {
          var consultantIds =
              participations.stream()
                  .filter(participation -> isModerator(participation.getRole()))
                  .map(GroupChatParticipant::getConsultantId)
                  .filter(Objects::nonNull)
                  .distinct()
                  .toList();
          if (consultantIds.isEmpty()) {
            return List.<String>of();
          }
          return consultantRepository.findAllByIdIn(consultantIds).stream()
              .map(Consultant::getMatrixUserId)
              .filter(id -> !isBlank(id))
              .distinct()
              .toList();
        });
  }

  private static String roomIdOf(Chat chat) {
    return chat != null && MatrixIds.isRoomId(chat.getMatrixRoomId())
        ? chat.getMatrixRoomId()
        : null;
  }

  private static String ownerOf(Chat chat) {
    return chat.getChatOwner() == null ? null : chat.getChatOwner().getMatrixUserId();
  }
}
