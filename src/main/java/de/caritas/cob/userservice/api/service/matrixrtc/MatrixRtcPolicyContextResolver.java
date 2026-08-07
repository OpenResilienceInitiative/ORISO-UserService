package de.caritas.cob.userservice.api.service.matrixrtc;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class MatrixRtcPolicyContextResolver {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ChatRepository chatRepository;
  private final @NonNull SessionSupervisorRepository sessionSupervisorRepository;
  private final @NonNull TeamDiscussionRepository teamDiscussionRepository;

  @Transactional(readOnly = true)
  public Optional<MatrixRtcPolicyContext> resolve(String sourceRoomId) {
    var supervision = sessionSupervisorRepository.findByMatrixRoomId(sourceRoomId);
    if (supervision.isPresent()) {
      return Optional.of(
          new MatrixRtcPolicyContext(
              supervision.get().getSession().getTenantId(),
              MatrixRtcPolicyContext.ChatType.SUPERVISION));
    }

    var session = sessionRepository.findByMatrixRoomId(sourceRoomId);
    if (session.isPresent()) {
      return Optional.of(contextForSession(session.get()));
    }

    var chat = chatRepository.findByMatrixRoomId(sourceRoomId);
    if (chat.isPresent()) {
      return Optional.of(contextForChat(chat.get()));
    }

    return teamDiscussionRepository
        .findByMatrixRoomId(sourceRoomId)
        .map(
            discussion ->
                new MatrixRtcPolicyContext(
                    discussion.getTenantId(), MatrixRtcPolicyContext.ChatType.GROUP));
  }

  private MatrixRtcPolicyContext contextForSession(Session session) {
    ConversationType conversationType = session.getConversationType();
    MatrixRtcPolicyContext.ChatType chatType;
    if (conversationType == ConversationType.LIVE_CHAT
        || (conversationType == null
            && session.getRegistrationType() == Session.RegistrationType.ANONYMOUS)) {
      chatType = MatrixRtcPolicyContext.ChatType.ANONYMOUS;
    } else if (conversationType == ConversationType.INTERNAL_GROUP) {
      chatType = MatrixRtcPolicyContext.ChatType.GROUP;
    } else {
      chatType = MatrixRtcPolicyContext.ChatType.ONE_ON_ONE;
    }
    return new MatrixRtcPolicyContext(session.getTenantId(), chatType);
  }

  private MatrixRtcPolicyContext contextForChat(Chat chat) {
    Long tenantId = chat.getChatOwner() == null ? null : chat.getChatOwner().getTenantId();
    return new MatrixRtcPolicyContext(tenantId, MatrixRtcPolicyContext.ChatType.GROUP);
  }
}
