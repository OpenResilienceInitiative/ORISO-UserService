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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Keeps a group's Matrix power levels in line with its roles (ORISO-Frontend#2229). Moderators of a
 * group are its owners and its named co-moderators (product owner, 12 Nov 2026); they hold at least
 * the Matrix moderator level (50), which lets them blur passages ({@code org.oriso.passage_blur})
 * and lift any blur ({@code redact}). A participant, or someone who left, holds no moderator level.
 * The room creator keeps 100: nobody above the moderator level is ever lowered.
 *
 * <p>Call after every role transition: creation, invitation, admission, role change, ownership
 * transfer, removal and leave. Raising is best effort and reported: a missing level only withholds
 * blurring. Taking the level back on a demotion is strict ({@link #reconcileOrFail}), so a demoted
 * moderator never keeps it.
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
   * Raises every moderator of the group in {@code roomId}, the freshly created room of its next
   * occurrence, which starts with the creator as its only moderator.
   */
  public boolean grantModeratorsOf(Chat chat, String roomId) {
    // A co-moderator may come from another Träger (recipient scope PLATFORM): resolved by
    // participation, across Träger.
    var moderatorIds =
        TenantContext.supplyAcrossTenants(
            () ->
                participantRepository.findBySeriesId(chat.getId()).stream()
                    .filter(participation -> isModerator(participation.getRole()))
                    .map(GroupChatParticipant::getConsultantId)
                    .filter(Objects::nonNull)
                    .map(consultantRepository::findById)
                    .flatMap(Optional::stream)
                    .map(Consultant::getMatrixUserId)
                    .toList());
    return grantModerators(roomId, ownerOf(chat), moderatorIds);
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
