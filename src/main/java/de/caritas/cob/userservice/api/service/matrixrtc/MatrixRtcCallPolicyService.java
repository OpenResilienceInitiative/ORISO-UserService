package de.caritas.cob.userservice.api.service.matrixrtc;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatrixRtcCallPolicyService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ChatRepository chatRepository;
  private final @NonNull SessionSupervisorRepository sessionSupervisorRepository;
  private final @NonNull TeamDiscussionRepository teamDiscussionRepository;
  private final @NonNull TenantService tenantService;
  private final @NonNull MatrixSynapseService matrixSynapseService;

  @Transactional(readOnly = true)
  public CallMediaPolicy resolve(String sourceRoomId, String matrixUserId) {
    if (sourceRoomId == null
        || sourceRoomId.isBlank()
        || matrixUserId == null
        || matrixUserId.isBlank()) {
      return CallMediaPolicy.denied();
    }

    var currentMembers = matrixSynapseService.getRoomMembers(sourceRoomId);
    if (currentMembers.isEmpty() || !currentMembers.get().contains(matrixUserId)) {
      return CallMediaPolicy.denied();
    }

    Optional<PolicyContext> context = resolveContext(sourceRoomId);
    if (context.isEmpty() || context.get().tenantId() == null) {
      return CallMediaPolicy.denied();
    }

    var tenant = tenantService.getRestrictedTenantDataFresh(context.get().tenantId());
    if (tenant == null || tenant.getSettings() == null) {
      return CallMediaPolicy.denied();
    }

    var settings = tenant.getSettings();
    if (!enabled(settings.getFeatureCallsEnabled())) {
      return CallMediaPolicy.denied();
    }

    boolean audioAllowed =
        enabled(settings.getFeatureAudioCallsEnabled())
            && enabled(audioFlag(settings, context.get().chatType()));
    boolean videoAllowed =
        enabled(settings.getFeatureVideoCallsEnabled())
            && enabled(videoFlag(settings, context.get().chatType()));
    return new CallMediaPolicy(audioAllowed, videoAllowed);
  }

  private Optional<PolicyContext> resolveContext(String sourceRoomId) {
    var supervision = sessionSupervisorRepository.findByMatrixRoomId(sourceRoomId);
    if (supervision.isPresent()) {
      return Optional.of(
          new PolicyContext(supervision.get().getSession().getTenantId(), ChatType.SUPERVISION));
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
        .map(discussion -> new PolicyContext(discussion.getTenantId(), ChatType.GROUP));
  }

  private PolicyContext contextForSession(Session session) {
    ConversationType conversationType = session.getConversationType();
    ChatType chatType;
    if (conversationType == ConversationType.LIVE_CHAT
        || (conversationType == null
            && session.getRegistrationType() == Session.RegistrationType.ANONYMOUS)) {
      chatType = ChatType.ANONYMOUS;
    } else if (conversationType == ConversationType.INTERNAL_GROUP) {
      chatType = ChatType.GROUP;
    } else {
      chatType = ChatType.ONE_ON_ONE;
    }
    return new PolicyContext(session.getTenantId(), chatType);
  }

  private PolicyContext contextForChat(Chat chat) {
    Long tenantId = chat.getChatOwner() == null ? null : chat.getChatOwner().getTenantId();
    return new PolicyContext(tenantId, ChatType.GROUP);
  }

  private Boolean audioFlag(Settings settings, ChatType chatType) {
    return switch (chatType) {
      case ANONYMOUS -> settings.getFeatureAudioCallsAnonymousChatsEnabled();
      case ONE_ON_ONE -> settings.getFeatureAudioCallsOneOnOneChatsEnabled();
      case GROUP -> settings.getFeatureAudioCallsGroupChatsEnabled();
      case SUPERVISION -> settings.getFeatureAudioCallsSupervisionChatsEnabled();
    };
  }

  private Boolean videoFlag(Settings settings, ChatType chatType) {
    return switch (chatType) {
      case ANONYMOUS -> settings.getFeatureVideoCallsAnonymousChatsEnabled();
      case ONE_ON_ONE -> settings.getFeatureVideoCallsOneOnOneChatsEnabled();
      case GROUP -> settings.getFeatureVideoCallsGroupChatsEnabled();
      case SUPERVISION -> settings.getFeatureVideoCallsSupervisionChatsEnabled();
    };
  }

  private boolean enabled(Boolean value) {
    return !Boolean.FALSE.equals(value);
  }

  private enum ChatType {
    ANONYMOUS,
    ONE_ON_ONE,
    GROUP,
    SUPERVISION
  }

  private record PolicyContext(Long tenantId, ChatType chatType) {}
}
