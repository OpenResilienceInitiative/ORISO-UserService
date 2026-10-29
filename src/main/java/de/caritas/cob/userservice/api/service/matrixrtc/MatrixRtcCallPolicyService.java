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
  private final @NonNull MatrixRtcCorrelationIdHasher correlationIdHasher;

  private final @NonNull org.springframework.jdbc.core.JdbcTemplate jdbc;

  public CallMediaPolicy resolve(String sourceRoomId, String matrixUserId) {
    return resolve(sourceRoomId, matrixUserId, null);
  }

  /**
   * @param callRoomId the call room being joined; a call guest (US#1477) is admitted only to that
   *     call, so without it the guest path stays closed
   */
  public CallMediaPolicy resolve(String sourceRoomId, String matrixUserId, String callRoomId) {
    if (sourceRoomId == null
        || sourceRoomId.isBlank()
        || matrixUserId == null
        || matrixUserId.isBlank()) {
      return CallMediaPolicy.denied();
    }

    // Public callback without a tenant; the room lookup is deliberately cross-tenant.
    return TenantContext.supplyAcrossTenants(
        () -> resolveCrossTenant(sourceRoomId, matrixUserId, callRoomId));
  }

  private CallMediaPolicy resolveCrossTenant(
      String sourceRoomId, String matrixUserId, String callRoomId) {
    // Denial logs below must never carry the raw Matrix room id / user id: those identify a
    // conversation and its participant to anyone with log access. correlationId is a keyed
    // (HMAC) hash of the pair, stable for this request, so denials for the same room+user can
    // still be correlated across log lines without exposing either identifier or letting a log
    // consumer confirm a candidate pair by hashing it themselves.
    var correlationId = correlationIdHasher.correlationId(sourceRoomId, matrixUserId);

    // Only persisted Matrix bindings identify the realm subject. Never infer it from a localpart.
    var identities =
        jdbc.queryForList(
            "SELECT user_id FROM user WHERE matrix_user_id=? UNION SELECT consultant_id FROM consultant WHERE matrix_user_id=?",
            String.class,
            matrixUserId,
            matrixUserId);
    if (identities.isEmpty()) {
      log.info(
          "Call policy denied [{}]: reason={}",
          correlationId,
          CallPolicyDenialReason.MATRIX_IDENTITY_NOT_FOUND);
      return CallMediaPolicy.denied();
    }
    if (identities.size() != 1) {
      log.info(
          "Call policy denied [{}]: reason={}",
          correlationId,
          CallPolicyDenialReason.MATRIX_IDENTITY_AMBIGUOUS);
      return CallMediaPolicy.denied();
    }
    var states =
        jdbc.queryForList(
            "SELECT status FROM account_inactivity WHERE identity_id=?",
            String.class,
            identities.getFirst());
    if (states.size() != 1) {
      log.info(
          "Call policy denied [{}]: reason={}",
          correlationId,
          CallPolicyDenialReason.INACTIVITY_STATE_UNAVAILABLE);
      return CallMediaPolicy.denied();
    }
    if (!"ACTIVE".equals(states.getFirst())) {
      log.info(
          "Call policy denied [{}]: reason={}",
          correlationId,
          CallPolicyDenialReason.ACCOUNT_NOT_ACTIVE);
      return CallMediaPolicy.denied();
    }

    // Knock first (ADR-029 §2): while the person's latest direct-link knock on this conversation
    // is waiting, declined or cancelled, the call stays closed to them, whatever the client does.
    var latestKnock =
        jdbc.queryForList(
            "SELECT r.status FROM group_chat_join_request r JOIN session s ON s.id = r.session_id"
                + " WHERE s.matrix_room_id = ? AND r.user_id = ? AND r.via = 'DIRECT_LINK'"
                + " ORDER BY r.id DESC LIMIT 1",
            String.class,
            sourceRoomId,
            identities.getFirst());
    if (!latestKnock.isEmpty() && !"ADMITTED".equals(latestKnock.getFirst())) {
      log.info(
          "Call policy denied [{}]: reason={}",
          correlationId,
          CallPolicyDenialReason.KNOCK_NOT_ADMITTED);
      return CallMediaPolicy.denied();
    }

    var currentMembers = matrixSynapseService.getRoomMembers(sourceRoomId);
    if (currentMembers.isEmpty()) {
      log.info(
          "Call policy denied [{}]: reason={}",
          correlationId,
          CallPolicyDenialReason.ROOM_MEMBERS_UNAVAILABLE);
      return CallMediaPolicy.denied();
    }
    if (!currentMembers.get().contains(matrixUserId)
        && !isActiveCallGuest(sourceRoomId, callRoomId, matrixUserId)) {
      log.info(
          "Call policy denied [{}]: reason={}",
          correlationId,
          CallPolicyDenialReason.NOT_ROOM_MEMBER);
      return CallMediaPolicy.denied();
    }

    var context = contextResolver.resolve(sourceRoomId);
    if (context.isEmpty() || context.get().tenantId() == null) {
      log.info(
          "Call policy denied [{}]: reason={}",
          correlationId,
          CallPolicyDenialReason.NO_TENANT_CONTEXT);
      return CallMediaPolicy.denied();
    }

    var tenant = getTenant(context.get().tenantId());
    if (tenant == null || tenant.getSettings() == null) {
      log.info(
          "Call policy denied [{}]: reason={}, tenant={}",
          correlationId,
          CallPolicyDenialReason.TENANT_SETTINGS_UNAVAILABLE,
          context.get().tenantId());
      return CallMediaPolicy.denied();
    }

    var settings = tenant.getSettings();
    if (!enabled(settings.getFeatureCallsEnabled())) {
      log.info(
          "Call policy denied [{}]: reason={}, tenant={}",
          correlationId,
          CallPolicyDenialReason.CALLS_DISABLED_FOR_TENANT,
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

  /**
   * US#1477: a call guest is no member of the source conversation. The counsellor's admission to
   * the still running call stands in for that membership, and only until the seat is revoked.
   */
  private boolean isActiveCallGuest(String sourceRoomId, String callRoomId, String matrixUserId) {
    // A seat belongs to one call: never accept it for another call of the same conversation.
    if (callRoomId == null || callRoomId.isBlank()) return false;
    try {
      return !jdbc.queryForList(
              "SELECT g.id FROM matrix_call_guest g JOIN matrix_call_binding b ON b.id = g.binding_id"
                  + " WHERE b.source_room_id = ? AND b.media_room_id = ? AND b.ended_at IS NULL"
                  + " AND g.matrix_user_id = ? AND g.revoked_at IS NULL",
              Long.class,
              sourceRoomId,
              callRoomId,
              matrixUserId)
          .isEmpty();
    } catch (org.springframework.dao.DataAccessException unavailable) {
      // Deny, but visibly: an outage must not read as an ordinary non-member denial.
      log.warn("Call guest seat lookup failed: {}", unavailable.getClass().getSimpleName());
      return false;
    }
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
