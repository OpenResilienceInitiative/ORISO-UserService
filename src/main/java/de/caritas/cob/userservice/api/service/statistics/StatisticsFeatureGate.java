package de.caritas.cob.userservice.api.service.statistics;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Honours the per-organisation statistics switch ({@code featureStatisticsEnabled} of the tenant
 * settings) where statistics data is collected. Counting is an opt-in of the organisation: only an
 * explicit {@code true} allows it, so an unset switch, an unknown tenant or an unreachable
 * TenantService all mean "do not count". The lookup is deliberately uncached; a cached copy would
 * keep counting for as long as it lives after an organisation switched statistics off.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsFeatureGate {

  private final @NonNull TenantService tenantService;

  @Value("${multitenancy.enabled:true}")
  private boolean multitenancy;

  /**
   * @param tenantId the tenant of the data being collected; {@code null} means single tenancy
   */
  public boolean isEnabledForTenant(Long tenantId) {
    if (TenantContext.TECHNICAL_TENANT_ID.equals(tenantId) || tenantId == null && multitenancy) {
      return false;
    }
    try {
      RestrictedTenantDTO tenant =
          tenantId != null
              ? tenantService.getRestrictedTenantDataFresh(tenantId)
              : tenantService.getSingleTenancyTenantDataFresh();
      if (tenant == null
          || tenant.getSettings() == null
          || tenantId != null && !tenantId.equals(tenant.getId())) {
        return false;
      }
      return Boolean.TRUE.equals(tenant.getSettings().getFeatureStatisticsEnabled());
    } catch (RuntimeException e) {
      log.warn(
          "Could not read the statistics switch of tenant {}; not counting. Cause: {}",
          tenantId,
          e.getClass().getSimpleName());
      return false;
    }
  }
}
