package de.caritas.cob.userservice.api.tenant;

import de.caritas.cob.userservice.api.model.TenantAware;
import de.caritas.cob.userservice.api.model.TenantFilter;
import java.util.Objects;
import org.hibernate.Hibernate;
import org.hibernate.annotations.Filter;
import org.hibernate.event.spi.LoadEvent;
import org.hibernate.event.spi.LoadEventListener;
import org.hibernate.internal.FilterImpl;

/**
 * Makes a lookup by primary key ({@code findById}, {@code EntityManager#find}) respect the tenant
 * boundary that the {@link TenantFilter} draws for queries. Hibernate filters never apply to such
 * lookups, so without this a Träger admin could open another Träger's row just by knowing its id.
 *
 * <p>Runs after Hibernate's own load listener, only for {@link LoadEventListener#GET} (the root
 * lookup by id). An entity that carries the tenant filter and belongs to <i>another</i> tenant is
 * reported as not found, exactly as a query would leave it out. Associations and proxies are not
 * touched: they are reached through a root that was already checked, and hiding them would break
 * the object graph.
 *
 * <p>Unlike the query filter, a row without a tenant is not hidden here: it belongs to no other
 * tenant, and legacy rows and entities saved earlier in the same transaction (tenant stamped only
 * on flush) must stay loadable by id.
 */
public class TenantScopedLoadByIdListener implements LoadEventListener {

  private static final ClassValue<Boolean> TENANT_FILTERED =
      new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
          if (!TenantAware.class.isAssignableFrom(type)) {
            return false;
          }
          for (var current = type; current != null; current = current.getSuperclass()) {
            for (Filter filter : current.getAnnotationsByType(Filter.class)) {
              if (TenantFilter.NAME.equals(filter.name())) {
                return true;
              }
            }
          }
          return false;
        }
      };

  @Override
  public void onLoad(LoadEvent event, LoadType loadType) {
    if (loadType != GET || event.getResult() == null) {
      return;
    }
    var entity = event.getResult();
    if (!TENANT_FILTERED.get(Hibernate.getClass(entity))) {
      return;
    }
    var currentTenant = currentTenantOf(event);
    if (currentTenant == null || TenantFilterParameterResolver.UNRESTRICTED.equals(currentTenant)) {
      return;
    }
    var rowTenant = ((TenantAware) Hibernate.unproxy(entity)).getTenantId();
    if (rowTenant != null && !Objects.equals(rowTenant, currentTenant)) {
      event.setResult(null);
    }
  }

  /** The argument the tenant filter uses in this session right now, {@code null} if it is off. */
  private static Long currentTenantOf(LoadEvent event) {
    var filter = event.getSession().getLoadQueryInfluencers().getEnabledFilter(TenantFilter.NAME);
    if (!(filter instanceof FilterImpl tenantFilter)) {
      return null;
    }
    return (Long) tenantFilter.getParameterValue(TenantFilter.PARAMETER);
  }
}
