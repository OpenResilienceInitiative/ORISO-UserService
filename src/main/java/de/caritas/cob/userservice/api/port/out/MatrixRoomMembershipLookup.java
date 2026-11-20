package de.caritas.cob.userservice.api.port.out;

import java.util.List;
import java.util.Optional;

/**
 * Outbound question to the chat server: which rooms does an account sit in right now.
 *
 * <p>Kept apart from {@link MatrixUserClient} (account operations) so that callers that only need
 * the membership, such as the administration module, depend on nothing else.
 */
public interface MatrixRoomMembershipLookup {

  /**
   * Like asking for the joined rooms, but tells "in no room" (an empty list) from "could not ask"
   * (empty result), for callers that must not mistake an outage for an empty membership.
   *
   * @param matrixUserId full local Matrix user ID
   * @return the joined room ids, or empty when the server could not be asked or answered without
   *     the list
   */
  Optional<List<String>> findJoinedRoomsForMatrixUser(String matrixUserId);
}
