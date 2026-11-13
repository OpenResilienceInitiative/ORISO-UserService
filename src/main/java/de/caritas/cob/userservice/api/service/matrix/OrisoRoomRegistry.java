package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.port.out.ChatMatrixRoomRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * FE#2209: whether a Matrix room is one of ORISO's own conversation rooms (session, group chat and
 * its occurrence rooms, team discussion, supervisor room, support room). Such a room is never a
 * dedicated call room, so it can never be bound as one.
 */
@Component
@RequiredArgsConstructor
public class OrisoRoomRegistry {
  private final SessionRepository sessions;
  private final ChatRepository chats;
  private final ChatMatrixRoomRepository chatRooms;
  private final TeamDiscussionRepository discussions;
  private final SessionSupervisorRepository supervisors;
  private final SupportRoomRepository supportRooms;

  @Transactional(readOnly = true)
  public boolean isKnownConversationRoom(String room) {
    return sessions.findByMatrixRoomId(room).isPresent()
        || chats.existsByMatrixRoomId(room)
        || chatRooms.existsById(room)
        || discussions.existsByMatrixRoomId(room)
        || supervisors.existsByMatrixRoomId(room)
        || supportRooms.existsByMatrixRoomId(room);
  }
}
