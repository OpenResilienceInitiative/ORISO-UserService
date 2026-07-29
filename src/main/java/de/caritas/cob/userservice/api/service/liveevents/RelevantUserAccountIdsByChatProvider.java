package de.caritas.cob.userservice.api.service.liveevents;

import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Provider to observe all assigned chat user ids instead of the initiator. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelevantUserAccountIdsByChatProvider implements UserIdsProvider {

  private final @NonNull ChatRepository chatRepository;
  private final @NonNull GroupChatMembershipService groupChatMembershipService;

  /**
   * Collects all relevant user account ids of a chat from its Matrix room membership.
   *
   * @param matrixRoomId Matrix room ID
   * @return a {@link List} containing all account ids to be notified; empty when the room state
   *     cannot be determined
   */
  @Override
  public List<String> collectUserIds(String matrixRoomId) {
    var resolvedRoomId =
        chatRepository
            .findByMatrixRoomId(matrixRoomId)
            .map(groupChatMembershipService::resolveMatrixRoomId)
            .orElse(matrixRoomId);

    return groupChatMembershipService.resolveHumanMembers(resolvedRoomId).stream()
        .map(ResolvedRoomMember::accountId)
        .toList();
  }
}
