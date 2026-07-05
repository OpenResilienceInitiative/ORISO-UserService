package de.caritas.cob.userservice.api.service.matrix;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Answers "who is (still) in this group chat?" from the Matrix room state.
 *
 * <p>Replaces the former Rocket.Chat member query in the leave-chat path: with Rocket.Chat disabled
 * (the default since ADR-004), {@code getStandardMembersOfGroup} always returns an empty list,
 * which made every single leave look like "the last member left" and deleted the chat for everyone.
 *
 * <p>The Matrix room membership (Synapse admin API) is the source of truth here because it is the
 * only membership record that is reliably maintained in the Matrix-only world: consultants join the
 * room on chat creation, askers join through their Matrix clients. The database relations are
 * write-only snapshots ({@code group_chat_participant} records creation-time invitees only, {@code
 * user_chat} was never cleaned up on leave) and would under-count, risking wrong deletion.
 *
 * <p>All decisions fail safe: when the room state cannot be determined, the chat is treated as
 * still having members, so it is never deleted on uncertainty.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupChatMembershipService {

  /**
   * Prefix of the technical group chat system users created by {@code
   * CreateChatFacade#resolveOrCreateGroupChatSystemUser} ({@code group-chat-system} and {@code
   * group-chat-system-<tenantId>}). These accounts must never count as chat members.
   */
  private static final String GROUP_CHAT_SYSTEM_USER_PREFIX = "group-chat-system";

  private final MatrixSynapseService matrixSynapseService;
  private final ConsultantRepository consultantRepository;
  private final UserRepository userRepository;

  /**
   * Best-effort removal of the leaving member from the chat's Matrix room. Failures are logged but
   * never abort the leave operation.
   *
   * @param chat the group chat being left
   * @param leavingMatrixUserId the full Matrix user ID of the leaving member, may be null
   */
  public void removeLeavingMemberFromRoom(Chat chat, String leavingMatrixUserId) {
    var matrixRoomId = resolveMatrixRoomId(chat);
    if (isBlank(matrixRoomId) || isBlank(leavingMatrixUserId)) {
      return;
    }

    try {
      var leaverToken = matrixSynapseService.loginAsUserAccessToken(leavingMatrixUserId);
      if (leaverToken == null) {
        log.warn(
            "Could not mint Matrix token for {}; leaving member stays in room {} of chat {}",
            leavingMatrixUserId,
            matrixRoomId,
            chat.getId());
        return;
      }
      if (!matrixSynapseService.leaveRoom(matrixRoomId, leaverToken)) {
        log.warn(
            "Could not remove leaving member {} from Matrix room {} of chat {}",
            leavingMatrixUserId,
            matrixRoomId,
            chat.getId());
      }
    } catch (Exception e) {
      log.warn(
          "Matrix room leave failed for member {} in room {} of chat {}: {}",
          leavingMatrixUserId,
          matrixRoomId,
          chat.getId(),
          e.getMessage());
    }
  }

  /**
   * Determines whether any human member (consultant or adviceseeker) other than the leaving user is
   * still joined to the chat's Matrix room. Technical accounts (Synapse admin, agency service
   * accounts, group chat system users) do not count.
   *
   * @param chat the group chat being left
   * @param leavingMatrixUserId the full Matrix user ID of the leaving member, may be null
   * @return true when other human members remain or the room state cannot be determined (fail
   *     safe), false only when the room is verifiably empty of other human members
   */
  public boolean hasRemainingHumanMembers(Chat chat, String leavingMatrixUserId) {
    var matrixRoomId = resolveMatrixRoomId(chat);
    if (isBlank(matrixRoomId)) {
      log.warn(
          "Chat {} has no Matrix room id; keeping the chat because remaining members cannot be"
              + " determined",
          chat.getId());
      return true;
    }

    var members = matrixSynapseService.getRoomMembers(matrixRoomId);
    if (members.isEmpty()) {
      log.warn(
          "Could not read members of Matrix room {} for chat {}; keeping the chat",
          matrixRoomId,
          chat.getId());
      return true;
    }

    return members.get().stream()
        .filter(memberId -> !memberId.equals(leavingMatrixUserId))
        .anyMatch(this::isHumanAppMember);
  }

  private boolean isHumanAppMember(String matrixUserId) {
    if (consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(matrixUserId).isPresent()) {
      return true;
    }
    return userRepository
        .findByMatrixUserIdAndDeleteDateIsNull(matrixUserId)
        .map(user -> !isGroupChatSystemUser(user))
        .orElse(false);
  }

  private boolean isGroupChatSystemUser(User user) {
    return user.getUserId() != null && user.getUserId().startsWith(GROUP_CHAT_SYSTEM_USER_PREFIX);
  }

  private String resolveMatrixRoomId(Chat chat) {
    if (!isBlank(chat.getMatrixRoomId())) {
      return chat.getMatrixRoomId();
    }
    return MatrixIds.isRoomId(chat.getGroupId()) ? chat.getGroupId() : null;
  }
}
