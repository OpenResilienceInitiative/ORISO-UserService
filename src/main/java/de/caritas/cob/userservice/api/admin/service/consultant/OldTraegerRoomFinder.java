package de.caritas.cob.userservice.api.admin.service.consultant;

import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.port.out.MatrixRoomMembershipLookup;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRoomRow;
import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairService.RoomRef;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Finds every chat room of the Träger a counsellor is leaving that they can still read (#1421).
 *
 * <p>Ownership, team, supervision and co-access explain most of it, and the supervision side rooms
 * (ADR-008) are staff-only discussions of the same cases. What they do not explain is the silent
 * membership in the accepted cases of the department's other counsellors (ADR-002 §1): nothing in
 * the database points at the counsellor there, only the room membership does. So the chat server is
 * asked where the counsellor actually sits, and that is intersected with the rooms of the old
 * Träger. When it cannot be asked, every room of the old centres is returned instead: leaving a
 * room one never sat in changes nothing, staying in one is the leak.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OldTraegerRoomFinder {

  private static final List<CaseHandoverRequest.Status> GRANTED_STATES =
      List.of(
          CaseHandoverRequest.Status.GRANTED,
          CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT);

  /** Keeps the IN lists of the membership queries well below what the database accepts. */
  private static final int CHUNK_SIZE = 500;

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull MatrixRoomMembershipLookup roomMembershipLookup;

  /**
   * @param consultantId the counsellor
   * @param matrixUserId their chat account
   * @param previousTenantId the Träger they leave
   * @param oldAgencyIds the centres they leave
   * @return one entry per room, with the case it belongs to; no case data
   */
  public List<RoomRef> roomsToLeave(
      String consultantId,
      String matrixUserId,
      Long previousTenantId,
      Collection<Long> oldAgencyIds) {
    // IN () is not valid SQL on every dialect: a counsellor without centre has no team rooms.
    Collection<Long> agencyIds = oldAgencyIds.isEmpty() ? List.of(-1L) : oldAgencyIds;
    var rooms = new LinkedHashMap<String, Long>();
    collect(
        rooms,
        sessionRepository.findRoomsReadableBy(
            previousTenantId, consultantId, agencyIds, GRANTED_STATES));
    collect(rooms, sessionRepository.findSupervisionSideRoomsOf(previousTenantId, consultantId));

    var joined = roomMembershipLookup.findJoinedRoomsForMatrixUser(matrixUserId);
    if (joined.isPresent()) {
      collectMemberRooms(rooms, joined.get(), previousTenantId);
    } else {
      log.warn(
          "Joined chat rooms of consultant {} could not be read; queueing every room of the"
              + " {} old centres instead",
          consultantId,
          oldAgencyIds.size());
      if (!oldAgencyIds.isEmpty()) {
        collect(rooms, sessionRepository.findSessionRoomsOfAgencies(oldAgencyIds));
        collect(rooms, sessionRepository.findSideRoomsOfAgencies(oldAgencyIds));
      }
    }
    return rooms.entrySet().stream()
        .map(room -> new RoomRef(room.getValue(), room.getKey()))
        .toList();
  }

  private void collectMemberRooms(
      Map<String, Long> rooms, List<String> joinedRoomIds, Long previousTenantId) {
    for (int from = 0; from < joinedRoomIds.size(); from += CHUNK_SIZE) {
      var chunk = joinedRoomIds.subList(from, Math.min(from + CHUNK_SIZE, joinedRoomIds.size()));
      collect(rooms, sessionRepository.findSessionRoomsOfTenantIn(previousTenantId, chunk));
      collect(rooms, sessionRepository.findSideRoomsOfTenantIn(previousTenantId, chunk));
    }
  }

  private static void collect(Map<String, Long> rooms, List<SessionRoomRow> rows) {
    rows.forEach(row -> rooms.putIfAbsent(row.roomId(), row.sessionId()));
  }
}
