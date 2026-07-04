package de.caritas.cob.userservice.api.service.matrix;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
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
    removeMemberFromRoom(matrixRoomId, leavingMatrixUserId);
  }

  /**
   * Best-effort removal of a member from a Matrix room, using the member's own admin-minted access
   * token to leave the room. Failures are logged but never thrown, so the caller's flow continues.
   *
   * <p>This is the Matrix-native replacement for a Rocket.Chat "remove user from group": leaving is
   * idempotent (already-gone counts as success) and needs no moderator token.
   *
   * @param matrixRoomId the Matrix room ID, may be blank
   * @param memberMatrixUserId the full Matrix user ID to remove, may be blank
   */
  public void removeMemberFromRoom(String matrixRoomId, String memberMatrixUserId) {
    if (isBlank(matrixRoomId) || isBlank(memberMatrixUserId)) {
      return;
    }

    try {
      var memberToken = matrixSynapseService.loginAsUserAccessToken(memberMatrixUserId);
      if (memberToken == null) {
        log.warn(
            "Could not mint Matrix token for {}; member stays in room {}",
            memberMatrixUserId,
            matrixRoomId);
        return;
      }
      if (!matrixSynapseService.leaveRoom(matrixRoomId, memberToken)) {
        log.warn(
            "Could not remove member {} from Matrix room {}", memberMatrixUserId, matrixRoomId);
      }
    } catch (Exception e) {
      log.warn(
          "Matrix room leave failed for member {} in room {}: {}",
          memberMatrixUserId,
          matrixRoomId,
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

  /**
   * A human member of a group chat room, resolved from a Matrix user ID to the matching application
   * account (consultant or adviceseeker). Technical accounts are never represented here.
   *
   * @param matrixUserId the full Matrix user ID (e.g. {@code @user:server})
   * @param accountId the application account ID ({@link Consultant#getId()} or {@link
   *     User#getUserId()})
   * @param username the application username, decoded where relevant
   * @param displayName the display name to show in the UI, or {@code null} when unknown
   * @param consultant {@code true} for a consultant, {@code false} for an adviceseeker
   */
  public record ResolvedRoomMember(
      String matrixUserId,
      String accountId,
      String username,
      String displayName,
      boolean consultant) {}

  /**
   * Resolves the current human members of a Matrix room to their application accounts.
   *
   * <p>Performs exactly one Synapse call ({@link MatrixSynapseService#getRoomMembers}) followed by
   * cheap database look-ups per member — never a Synapse call per member — so it is safe to use on
   * hot paths. Technical accounts (Synapse admin, agency service accounts, group chat system users)
   * are filtered out.
   *
   * <p>Returns an empty list when the room state cannot be determined. Callers that use the result
   * for a read-only purpose (member lists, notification fan-out) can treat empty as "nobody to act
   * on"; callers that use it to drive a destructive decision must NOT — they should first consult
   * {@link #hasRemainingHumanMembers} or otherwise guard against the empty-on-uncertainty case.
   *
   * @param matrixRoomId the Matrix room ID, may be blank
   * @return the resolved human members, or an empty list when unknown / no human members
   */
  public List<ResolvedRoomMember> resolveHumanMembers(String matrixRoomId) {
    if (isBlank(matrixRoomId)) {
      return List.of();
    }

    var members = matrixSynapseService.getRoomMembers(matrixRoomId);
    if (members.isEmpty()) {
      log.warn(
          "Could not read members of Matrix room {}; treating as no resolvable members",
          matrixRoomId);
      return List.of();
    }

    return members.get().stream()
        .map(this::resolveMember)
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  private ResolvedRoomMember resolveMember(String matrixUserId) {
    var consultant = consultantRepository.findByMatrixUserIdAndDeleteDateIsNull(matrixUserId);
    if (consultant.isPresent()) {
      var c = consultant.get();
      return new ResolvedRoomMember(
          matrixUserId, c.getId(), c.getUsername(), resolveConsultantDisplayName(c), true);
    }

    var user = userRepository.findByMatrixUserIdAndDeleteDateIsNull(matrixUserId);
    if (user.isPresent() && !isGroupChatSystemUser(user.get())) {
      var u = user.get();
      return new ResolvedRoomMember(
          matrixUserId, u.getUserId(), u.getUsername(), u.getUsername(), false);
    }

    // Unknown Matrix ID (technical account, deleted account, or foreign homeserver user).
    return null;
  }

  private String resolveConsultantDisplayName(Consultant consultant) {
    return !isBlank(consultant.getDisplayName())
        ? consultant.getDisplayName()
        : consultant.getFullName();
  }

  private boolean isHumanAppMember(String matrixUserId) {
    return resolveMember(matrixUserId) != null;
  }

  private boolean isGroupChatSystemUser(User user) {
    return user.getUserId() != null && user.getUserId().startsWith(GROUP_CHAT_SYSTEM_USER_PREFIX);
  }

  /**
   * Resolves the Matrix room ID of a group chat, preferring the dedicated {@code matrixRoomId}
   * column and falling back to {@code groupId} when that already holds a Matrix room ID.
   *
   * @param chat the group chat, may be null
   * @return the Matrix room ID, or {@code null} when the chat has no Matrix room
   */
  public String resolveMatrixRoomId(Chat chat) {
    if (chat == null) {
      return null;
    }
    if (!isBlank(chat.getMatrixRoomId())) {
      return chat.getMatrixRoomId();
    }
    return MatrixIds.isRoomId(chat.getGroupId()) ? chat.getGroupId() : null;
  }

  /**
   * Resolves the Matrix room ID of a session, preferring the dedicated {@code matrixRoomId} column
   * and falling back to {@code groupId} when that already holds a Matrix room ID.
   *
   * @param session the session, may be null
   * @return the Matrix room ID, or {@code null} when the session has no Matrix room
   */
  public String resolveMatrixRoomId(Session session) {
    if (session == null) {
      return null;
    }
    if (!isBlank(session.getMatrixRoomId())) {
      return session.getMatrixRoomId();
    }
    return MatrixIds.isRoomId(session.getGroupId()) ? session.getGroupId() : null;
  }
}
