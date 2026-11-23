package de.caritas.cob.userservice.api.tenant;

import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Supplies the tenant filter's argument from the current {@link TenantContext}. A thread without a
 * tenant gets one that matches no tenant row, so a missing context fails closed.
 */
@Component
public class TenantFilterParameterResolver implements Supplier<Long> {

  static final Long UNRESTRICTED = TenantContext.TECHNICAL_TENANT_ID;
  static final Long NO_TENANT = -1L;

  // Field default keeps the filter on should Hibernate create this class without Spring.
  @Value("${multitenancy.enabled:true}")
  private boolean multitenancyEnabled = true;

  @Override
  public Long get() {
    if (!multitenancyEnabled) {
      return UNRESTRICTED;
    }
    var currentTenant = TenantContext.getCurrentTenant();
    return currentTenant == null ? NO_TENANT : currentTenant;
  }
}
