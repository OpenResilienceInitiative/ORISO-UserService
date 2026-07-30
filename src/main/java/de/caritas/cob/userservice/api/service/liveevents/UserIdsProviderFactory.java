package de.caritas.cob.userservice.api.service.liveevents;

import de.caritas.cob.userservice.api.port.out.ChatRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Factory to decide which {@link UserIdsProvider} to be used for collecting user ids. */
@Component
@RequiredArgsConstructor
public class UserIdsProviderFactory {

  private final @NonNull RelevantUserAccountIdsByChatProvider byChatProvider;
  private final @NonNull RelevantUserAccountIdsBySessionProvider bySessionProvider;
  private final @NonNull ChatRepository chatRepository;

  /**
   * Provides the relevant {@link UserIdsProvider}.
   *
   * @param matrixRoomId Matrix room ID
   * @return {@link RelevantUserAccountIdsByChatProvider} if the room belongs to a chat and {@link
   *     RelevantUserAccountIdsBySessionProvider} if not
   */
  public UserIdsProvider forMatrixRoom(String matrixRoomId) {
    return isChat(matrixRoomId) ? this.byChatProvider : this.bySessionProvider;
  }

  private boolean isChat(String matrixRoomId) {
    return this.chatRepository.findByMatrixRoomId(matrixRoomId).isPresent();
  }
}
