package de.caritas.cob.userservice.api.tenant;

import de.caritas.cob.userservice.api.model.TenantAware;
import de.caritas.cob.userservice.api.model.TenantFilter;
import jakarta.persistence.EntityNotFoundException;
import java.util.Objects;
import org.hibernate.Hibernate;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.annotations.Filter;
import org.hibernate.event.spi.LoadEvent;
import org.hibernate.event.spi.LoadEventListener;

/**
 * Hibernate filters skip loads by primary key, so a row of another tenant is hidden here. Rows
 * without a tenant stay loadable; associations are reached through an already checked root.
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
    boolean findById = loadType == GET;
    boolean rootReference = loadType == LOAD && !event.isAssociationFetch();
    if ((!findById && !rootReference) || event.getResult() == null) {
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
    Long rowTenant;
    try {
      rowTenant = ((TenantAware) Hibernate.unproxy(entity)).getTenantId();
    } catch (ObjectNotFoundException | EntityNotFoundException missing) {
      // Left to Hibernate: a reference to a missing row fails on first use.
      return;
    }
    if (rowTenant == null || Objects.equals(rowTenant, currentTenant)) {
      return;
    }
    if (findById) {
      event.setResult(null);
    } else {
      throw new EntityNotFoundException(
          "No " + event.getEntityClassName() + " with id " + event.getEntityId());
    }
  }

  /** The argument the tenant filter uses in this session right now, {@code null} if it is off. */
  private static Long currentTenantOf(LoadEvent event) {
    var filter = event.getSession().getEnabledFilter(TenantFilter.NAME);
    return filter == null ? null : (Long) filter.getParameterValue(TenantFilter.PARAMETER);
  }
}
