package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Consultant;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Enforces the tenant-owned self-help group activation at the backend boundary. */
@Service
@RequiredArgsConstructor
public class GroupChatFeatureGate {

  private final @NonNull TenantService tenantService;

  public void requireEnabled(Consultant consultant) {
    if (consultant == null || consultant.getTenantId() == null) {
      throw disabled();
    }

    var tenant = tenantService.getRestrictedTenantDataFresh(consultant.getTenantId());
    if (tenant == null
        || tenant.getSettings() == null
        || !Boolean.TRUE.equals(tenant.getSettings().getFeatureGroupChatV2Enabled())) {
      throw disabled();
    }
  }

  private ForbiddenException disabled() {
    return new ForbiddenException("Self-help group chat is disabled for this tenant");
  }
}
