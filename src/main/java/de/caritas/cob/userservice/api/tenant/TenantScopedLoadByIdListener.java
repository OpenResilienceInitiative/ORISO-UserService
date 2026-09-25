package de.caritas.cob.userservice.api.tenant;

import de.caritas.cob.userservice.api.model.TenantAware;
import de.caritas.cob.userservice.api.model.TenantFilter;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.Hibernate;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.annotations.Filter;
import org.hibernate.event.spi.LoadEvent;
import org.hibernate.event.spi.LoadEventListener;

/**
 * Hibernate filters skip loads by primary key, so a row the entity's filter condition hides is
 * hidden here too, rows without a tenant included. Associations are reached through a checked root.
 */
public class TenantScopedLoadByIdListener implements LoadEventListener {

  /** Which condition of {@link TenantFilter} an entity declares, if any. */
  private enum Boundary {
    NONE,
    STRICT,
    LEGACY_ROWS_OF_TENANT_ONE
  }

  private static final Long LEGACY_TENANT = 1L;

  private static final ClassValue<Boundary> BOUNDARY =
      new ClassValue<>() {
        @Override
        protected Boundary computeValue(Class<?> type) {
          if (!TenantAware.class.isAssignableFrom(type)) {
            return Boundary.NONE;
          }
          for (var current = type; current != null; current = current.getSuperclass()) {
            for (Filter filter : current.getAnnotationsByType(Filter.class)) {
              if (TenantFilter.NAME.equals(filter.name())) {
                return TenantFilter.CONDITION_WITH_LEGACY_ROWS_OF_TENANT_ONE.equals(
                        filter.condition())
                    ? Boundary.LEGACY_ROWS_OF_TENANT_ONE
                    : Boundary.STRICT;
              }
            }
          }
          return Boundary.NONE;
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
    var boundary = BOUNDARY.get(Hibernate.getClass(entity));
    if (boundary == Boundary.NONE) {
      return;
    }
    var currentTenant = currentTenantOf(event);
    if (currentTenant == null || TenantFilterParameterResolver.UNRESTRICTED.equals(currentTenant)) {
      return;
    }
    Long rowTenant;
    // Side effect: a getReference() proxy is initialised here, one select earlier than without it.
    try {
      rowTenant = ((TenantAware) Hibernate.unproxy(entity)).getTenantId();
    } catch (ObjectNotFoundException | EntityNotFoundException missing) {
      // Left to Hibernate: a reference to a missing row fails on first use.
      return;
    }
    if (isVisible(boundary, rowTenant, currentTenant)) {
      return;
    }
    if (findById) {
      event.setResult(null);
    } else {
      throw new EntityNotFoundException(
          "No " + event.getEntityClassName() + " with id " + event.getEntityId());
    }
  }

  /** Mirrors the SQL condition of the entity's filter, so a list and a load agree. */
  private static boolean isVisible(Boundary boundary, Long rowTenant, Long currentTenant) {
    if (rowTenant == null) {
      return boundary == Boundary.LEGACY_ROWS_OF_TENANT_ONE && LEGACY_TENANT.equals(currentTenant);
    }
    return rowTenant.equals(currentTenant);
  }

  /** The argument the tenant filter uses in this session right now, {@code null} if it is off. */
  private static Long currentTenantOf(LoadEvent event) {
    var filter = event.getSession().getEnabledFilter(TenantFilter.NAME);
    return filter == null ? null : (Long) filter.getParameterValue(TenantFilter.PARAMETER);
  }
}
