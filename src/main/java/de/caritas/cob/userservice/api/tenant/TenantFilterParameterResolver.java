package de.caritas.cob.userservice.api.tenant;

import de.caritas.cob.userservice.api.model.TenantFilter;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Supplies the {@code tenantId} argument of the auto-enabled {@link TenantFilter}. Hibernate calls
 * it whenever a filtered query or a load by id runs, in whatever session that happens, so the
 * argument is always the tenant of the current thread at that moment.
 *
 * <p>Returns {@code 0} (no restriction) when
 *
 * <ul>
 *   <li>multitenancy is switched off — the single-tenant deployments never filtered;
 *   <li>the current tenant is the technical / platform tenant {@code 0};
 *   <li>no tenant is set: system threads (schedulers, async dispatch) and the few public routes
 *       that {@code HttpTenantFilter} deliberately skips (registration, magic and invite links,
 *       MatrixRTC policy). Every authenticated request passes {@code HttpTenantFilter}, which
 *       either sets a tenant or rejects the request, so a caller who belongs to a tenant is never
 *       unrestricted.
 * </ul>
 *
 * <p>Hibernate obtains this bean through Spring's bean container, so each application context uses
 * its own {@code multitenancy.enabled} value. The field default keeps the filter active should
 * Hibernate ever instantiate the class without Spring.
 */
@Component
public class TenantFilterParameterResolver implements Supplier<Long> {

  static final Long UNRESTRICTED = TenantContext.TECHNICAL_TENANT_ID;

  @Value("${multitenancy.enabled:true}")
  private boolean multitenancyEnabled = true;

  @Override
  public Long get() {
    if (!multitenancyEnabled) {
      return UNRESTRICTED;
    }
    var currentTenant = TenantContext.getCurrentTenant();
    return currentTenant == null ? UNRESTRICTED : currentTenant;
  }
}
