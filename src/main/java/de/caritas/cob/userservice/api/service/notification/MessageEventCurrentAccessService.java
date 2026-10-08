package de.caritas.cob.userservice.api.service.notification;

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
    if (caller.getTenantId() == null
        || !Objects.equals(effectiveTenant(session.getTenantId()), caller.getTenantId())) {
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
          || !Objects.equals(effectiveTenant(actor.getTenantId()), caller.getTenantId())
          || !(legacyIdentity
              ? writers.isCurrentAssignedOrTeamWriter(session, actor)
              : writers.isCurrentWriter(session, actor.getMatrixUserId(), false))) {
        throw new ForbiddenException(
            "Message event caller has no current primary-room write access");
      }
    }
  }

  private Long effectiveTenant(Long tenantId) {
    // TenantFilter.CONDITION_WITH_LEGACY_ROWS_OF_TENANT_ONE assigns only this context
    // ownership of legacy rows. Do not mutate stored models or infer another tenant.
    if (tenantId == null && Objects.equals(TenantContext.getCurrentTenant(), 1L)) return 1L;
    return tenantId;
  }
}
