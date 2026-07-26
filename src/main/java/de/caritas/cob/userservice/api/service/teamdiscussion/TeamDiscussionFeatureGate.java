package de.caritas.cob.userservice.api.service.teamdiscussion;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Enforces the Team-Besprechung feature toggle at the backend boundary (US#473 / ADR-016).
 *
 * <p>The per-tenant setting {@code featureTeamDiscussionEnabled} (TenantService) takes precedence.
 * When there is no tenant context — single-tenant deploy, or TenantService returns no settings —
 * the gate falls back to the deploy property {@code team-discussion.enabled} (default true). This
 * mirrors {@code GroupChatFeatureGate} but fails <em>open to the property</em> rather than closed,
 * so single-tenant installations keep working without a tenant settings document.
 */
@Service
@RequiredArgsConstructor
public class TeamDiscussionFeatureGate {

  private final @NonNull TenantService tenantService;

  @Value("${team-discussion.enabled:true}")
  private boolean teamDiscussionEnabled;

  public void requireEnabled(Long tenantId) {
    if (!isEnabled(tenantId)) {
      throw new ForbiddenException("Team discussion is disabled");
    }
  }

  public boolean isEnabled(Long tenantId) {
    Boolean tenantValue = resolveTenantValue(tenantId);
    return tenantValue != null ? tenantValue : teamDiscussionEnabled;
  }

  private Boolean resolveTenantValue(Long tenantId) {
    if (tenantId == null) {
      return null;
    }
    var tenant = tenantService.getRestrictedTenantDataFresh(tenantId);
    if (tenant == null || tenant.getSettings() == null) {
      return null;
    }
    return tenant.getSettings().getFeatureTeamDiscussionEnabled();
  }
}
