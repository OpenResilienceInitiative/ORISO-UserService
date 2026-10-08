package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatrixCallConversationResolver {
  private final SessionRepository sessions;
  private final ChatRepository chats;

  @Transactional(readOnly = true)
  public Optional<MatrixCallConversation> resolve(String room) {
    return resolve(room, true);
  }

  /** New call activity is separate from reading already-retained call history. */
  @Transactional(readOnly = true)
  public Optional<MatrixCallConversation> resolveForWriting(String room) {
    return resolve(room, false);
  }

  /** Caller holds this lock through its new call writes; retained reads use resolve instead. */
  @Transactional
  public boolean lockCurrentForWriting(MatrixCallConversation expected) {
    return expected.getSessionId() == null
        || sessions
            .lockCallStatus(
                expected.getSessionId(), expected.getMatrixRoomId(), expected.getTenantId())
            .filter(
                status ->
                    status
                        != de.caritas.cob.userservice.api.model.Session.SessionStatus.REJECTED
                            .getValue())
            .isPresent();
  }

  private Optional<MatrixCallConversation> resolve(String room, boolean retainedRead) {
    var session = sessions.findByMatrixRoomId(room);
    var chat = chats.findByMatrixRoomId(room);
    if (session.isPresent() && chat.isPresent()) return Optional.empty();
    if (session.isPresent()) {
      var value = session.get();
      if (!retainedRead
          && value.getStatus()
              == de.caritas.cob.userservice.api.model.Session.SessionStatus.REJECTED)
        return Optional.empty();
      if (value.getId() == null || value.getTenantId() == null) return Optional.empty();
      return Optional.of(
          new MatrixCallConversation(room, value.getTenantId(), value.getId(), null));
    }
    return chat.filter(
            value ->
                value.getId() != null
                    && value.getChatOwner() != null
                    && value.getChatOwner().getTenantId() != null)
        .map(
            value ->
                new MatrixCallConversation(
                    room, value.getChatOwner().getTenantId(), null, value.getId()));
  }
}
