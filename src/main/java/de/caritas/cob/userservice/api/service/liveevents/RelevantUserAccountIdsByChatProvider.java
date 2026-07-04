package de.caritas.cob.userservice.api.service.liveevents;

import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Provider to observe all assigned chat user ids instead of initiator.
 *
 * <p>Live events for a group chat reach everyone currently in the chat's Matrix room. Rocket.Chat
 * membership is no longer consulted (it is disabled since ADR-004, so it always returned nobody and
 * live updates silently reached no one).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelevantUserAccountIdsByChatProvider implements UserIdsProvider {

  private final @NonNull ChatRepository chatRepository;
  private final @NonNull GroupChatMembershipService groupChatMembershipService;

  /**
   * Collects all relevant user account ids of a chat from its Matrix room membership.
   *
   * @param groupId the chat group id (Matrix room id, or a legacy Rocket.Chat group id whose chat
   *     carries a Matrix room id)
   * @return a {@link List} containing all account ids to be notified; empty when the room state
   *     cannot be determined
   */
  @Override
  public List<String> collectUserIds(String groupId) {
    var matrixRoomId =
        chatRepository
            .findByGroupId(groupId)
            .map(groupChatMembershipService::resolveMatrixRoomId)
            .orElse(groupId);

    return groupChatMembershipService.resolveHumanMembers(matrixRoomId).stream()
        .map(ResolvedRoomMember::accountId)
        .toList();
  }
}
