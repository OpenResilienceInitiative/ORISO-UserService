package de.caritas.cob.userservice.api.service.matrixgroup;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Chat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Ends the retained native resources of a truly deleted Series, never an ordinary recurrence. */
@Service
@RequiredArgsConstructor
public class GroupMatrixLifecycleCleanup {
  private final MatrixGroupParticipationHistory participation;
  private final ChatMatrixRoomHistory rooms;
  private final MatrixSynapseService matrix;

  public void deleteSeries(Chat chat) {
    if (!participation.enabled()) return;
    var recorded = rooms.allRecordedRooms(chat);
    // Earlier best-effort retirement may have failed; do not abandon a retained/prepared room.
    // The current room keeps its existing caller-owned purge/transaction semantics.
    for (var room : recorded) {
      if (!room.equals(chat.getMatrixRoomId())
          && matrix.purgeRoomOrConfirmGone(room) == MatrixSynapseService.RoomPurgeOutcome.FAILED)
        throw MatrixGroupParticipationHistory.unavailable();
    }
    for (var room : recorded) participation.forgetRoom(room);
  }
}
