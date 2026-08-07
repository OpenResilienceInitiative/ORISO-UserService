package de.caritas.cob.userservice.api.service.matrixrtc;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
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

    // The call-policy endpoint is whitelisted from HttpTenantFilter, so this thread has no
    // tenant context. Without technical context TenantAspect enables the Hibernate
    // tenantFilter with tenantId=null, the room-to-session lookup matches nothing, and every
    // call is denied regardless of tenant settings.
    var callerTenant = TenantContext.getCurrentTenant();
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      return resolveCrossTenant(sourceRoomId, matrixUserId);
    } finally {
      if (callerTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.setCurrentTenant(callerTenant);
      }
    }
  }

  private CallMediaPolicy resolveCrossTenant(String sourceRoomId, String matrixUserId) {
    var currentMembers = matrixSynapseService.getRoomMembers(sourceRoomId);
    if (currentMembers.isEmpty() || !currentMembers.get().contains(matrixUserId)) {
      log.info(
          "Call policy denied for room {}: user {} is not a current room member",
          sourceRoomId,
          matrixUserId);
      return CallMediaPolicy.denied();
    }

    var context = contextResolver.resolve(sourceRoomId);
    if (context.isEmpty() || context.get().tenantId() == null) {
      log.info(
          "Call policy denied for room {}: no session, chat, supervision or team discussion with"
              + " a tenant id resolves to this room",
          sourceRoomId);
      return CallMediaPolicy.denied();
    }

    var tenant = getTenant(context.get().tenantId());
    if (tenant == null || tenant.getSettings() == null) {
      log.info(
          "Call policy denied for room {}: tenant {} settings could not be loaded",
          sourceRoomId,
          context.get().tenantId());
      return CallMediaPolicy.denied();
    }

    var settings = tenant.getSettings();
    if (!enabled(settings.getFeatureCallsEnabled())) {
      log.info(
          "Call policy denied for room {}: featureCallsEnabled is off for tenant {}",
          sourceRoomId,
          context.get().tenantId());
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
      log.warn(
          "Call policy tenant lookup failed for tenant {}: {}",
          tenantId,
          tenantServiceFailure.getMessage());
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
