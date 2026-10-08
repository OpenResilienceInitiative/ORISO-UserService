package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.model.TenantAware;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import org.hibernate.Interceptor;
import org.hibernate.type.Type;

/** Stamps newly persisted records without reassigning existing tenant ownership. */
public class TenantHibernateInterceptor implements Interceptor {
  @Override
  public boolean onPersist(
      Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
    if (!(entity instanceof TenantAware owned)
        || owned.getTenantId() != null
        || !TenantContext.contextIsSet()
        || TenantContext.isTechnicalOrSuperAdminContext()) {
      return false;
    }
    for (int index = 0; index < propertyNames.length; index++) {
      if ("tenantId".equals(propertyNames[index])) {
        Long tenantId = TenantContext.getCurrentTenant();
        owned.setTenantId(tenantId);
        state[index] = tenantId;
        return true;
      }
    }
    return false;
  }
}
