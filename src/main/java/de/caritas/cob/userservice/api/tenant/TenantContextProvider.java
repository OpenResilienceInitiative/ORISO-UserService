package de.caritas.cob.userservice.api.tenant;

import static de.caritas.cob.userservice.api.tenant.TenantResolverService.TECHNICAL_TENANT_ID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TenantContextProvider {

  @Value("${multitenancy.enabled}")
  private boolean multiTenancyEnabled;

  public boolean isMultiTenancyEnabled() {
    return multiTenancyEnabled;
  }

  public void setTechnicalContextIfMultiTenancyIsEnabled() {
    if (multiTenancyEnabled) {
      TenantContext.setCurrentTenant(TECHNICAL_TENANT_ID);
    }
  }

  public void setCurrentTenantContextIfMissing(Long currentTenantId) {
    if (!TenantContext.contextIsSet()) {
      TenantContext.setCurrentTenant(currentTenantId);
    }
  }

  /** Background work has no caller, so it runs in the technical tenant and leaves none behind. */
  public Runnable inTechnicalContext(Runnable task) {
    return () -> TenantContext.runWith(technicalTenant(), task);
  }

  /** Work handed to another thread keeps the tenant of the thread that handed it over. */
  public Runnable inCallersContext(Runnable task) {
    var callers = TenantContext.getCurrentTenantData();
    var copy =
        callers == null ? null : new TenantData(callers.getTenantId(), callers.getSubdomain());
    return () -> TenantContext.runWith(copy, task);
  }

  private TenantData technicalTenant() {
    return multiTenancyEnabled ? new TenantData(TECHNICAL_TENANT_ID, null) : null;
  }
}
