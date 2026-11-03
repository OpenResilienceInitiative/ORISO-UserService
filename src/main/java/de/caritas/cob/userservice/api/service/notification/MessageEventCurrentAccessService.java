package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Uses current case access and write authority before a browser can publish a primary-room event.
 */
@Service
@RequiredArgsConstructor
public class MessageEventCurrentAccessService {
  private final @NonNull SessionRepository sessions;
  private final @NonNull SessionService sessionService;
  private final @NonNull ConsultantRepository consultants;
  private final @NonNull MatrixCaseReplyActorAuthorizer writers;
  private final @NonNull TenantService tenants;

  @Value("${multitenancy.enabled}")
  private boolean multitenancyEnabled;

  public void assertPrimaryRoomWriter(String roomId, AuthenticatedUser caller) {
    var current = sessions.findByMatrixRoomId(roomId);
    if (current.isEmpty()) return;
    var session = current.get();
    if (session.getConversationType() != null
        && session.getConversationType() != ConversationType.AGENCY_COUNSELLING
        && session.getConversationType() != ConversationType.LIVE_CHAT) {
      // Separate group and protected-room producers retain their own current membership rules.
      return;
    }
    Long callerTenant = currentCallerTenant(caller);
    if (callerTenant == null
        || !Objects.equals(effectiveTenant(session.getTenantId()), callerTenant)) {
      throw new ForbiddenException("Message event caller has no current case tenant access");
    }
    sessionService.assertUserHasAccess(session.getId(), caller);
    if (session.getStatus()
        == de.caritas.cob.userservice.api.model.Session.SessionStatus.REJECTED) {
      throw new ForbiddenException("Rejected conversation is read-only");
    }
    if (caller.isConsultant()) {
      var actor = consultants.findByIdAndDeleteDateIsNull(caller.getUserId()).orElse(null);
      boolean legacyIdentity =
          session.getTenantId() == null || actor != null && actor.getTenantId() == null;
      if (actor == null
          || actor.getMatrixUserId() == null
          || actor.getMatrixUserId().isBlank()
          || session.getUser() == null
          || Objects.equals(actor.getId(), session.getUser().getUserId())
          || !Objects.equals(effectiveTenant(actor.getTenantId()), callerTenant)
          || !(legacyIdentity
              ? writers.isCurrentAssignedOrTeamWriter(session, actor)
              : writers.isCurrentWriter(session, actor.getMatrixUserId(), false))) {
        throw new ForbiddenException(
            "Message event caller has no current primary-room write access");
      }
    }
  }

  private Long currentCallerTenant(AuthenticatedUser caller) {
    if (caller.getTenantId() != null || multitenancyEnabled) return caller.getTenantId();
    return configuredSingleTenant();
  }

  private Long configuredSingleTenant() {
    try {
      // The server resolves exactly one configured tenant; the target case grants no authority.
      var tenant = tenants.getSingleTenancyTenantDataFresh();
      return tenant != null && tenant.getId() != null && tenant.getId() > 0 ? tenant.getId() : null;
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  private Long effectiveTenant(Long tenantId) {
    // TenantFilter.CONDITION_WITH_LEGACY_ROWS_OF_TENANT_ONE assigns only this context
    // ownership of legacy rows. Do not mutate stored models or infer another tenant.
    if (tenantId == null && Objects.equals(TenantContext.getCurrentTenant(), 1L)) return 1L;
    // Single-tenant rows may carry no tenant; they belong to the configured tenant only.
    if (tenantId == null && !multitenancyEnabled) return configuredSingleTenant();
    return tenantId;
  }
}
