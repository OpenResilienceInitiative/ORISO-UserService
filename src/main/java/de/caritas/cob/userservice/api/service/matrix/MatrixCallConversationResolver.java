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
    var session = sessions.findByMatrixRoomId(room);
    var chat = chats.findByMatrixRoomId(room);
    if (session.isPresent() && chat.isPresent()) return Optional.empty();
    if (session.isPresent()) {
      var value = session.get();
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
