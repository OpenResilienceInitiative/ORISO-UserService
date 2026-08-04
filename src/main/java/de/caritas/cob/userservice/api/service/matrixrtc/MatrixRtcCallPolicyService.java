package de.caritas.cob.userservice.api.service.matrixrtc;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MatrixRtcCallPolicyService {

  private final @NonNull MatrixRtcPolicyContextResolver contextResolver;
  private final @NonNull TenantService tenantService;
  private final @NonNull MatrixSynapseService matrixSynapseService;

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

    var context = contextResolver.resolve(sourceRoomId);
    if (context.isEmpty() || context.get().tenantId() == null) {
      return CallMediaPolicy.denied();
    }

    var tenant = getTenant(context.get().tenantId());
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

  private RestrictedTenantDTO getTenant(Long tenantId) {
    try {
      return tenantService.getRestrictedTenantDataFresh(tenantId);
    } catch (RuntimeException tenantServiceFailure) {
      return null;
    }
  }

  private Boolean audioFlag(Settings settings, MatrixRtcPolicyContext.ChatType chatType) {
    return switch (chatType) {
      case ANONYMOUS -> settings.getFeatureAudioCallsAnonymousChatsEnabled();
      case ONE_ON_ONE -> settings.getFeatureAudioCallsOneOnOneChatsEnabled();
      case GROUP -> settings.getFeatureAudioCallsGroupChatsEnabled();
      case SUPERVISION -> settings.getFeatureAudioCallsSupervisionChatsEnabled();
    };
  }

  private Boolean videoFlag(Settings settings, MatrixRtcPolicyContext.ChatType chatType) {
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
}
