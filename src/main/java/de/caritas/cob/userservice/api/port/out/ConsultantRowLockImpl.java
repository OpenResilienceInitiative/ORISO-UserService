package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.TenantFilter;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import org.hibernate.Session;

/** Spring Data fragment of {@link ConsultantRepository}. */
class ConsultantRowLockImpl implements ConsultantRowLock {
  @PersistenceContext private EntityManager entityManager;

  @Override
  public Optional<Consultant> findFreshActiveByIdForUpdate(String consultantId) {
    var filter = entityManager.unwrap(Session.class).getEnabledFilter(TenantFilter.NAME);
    var tenantId =
        filter == null
            ? TenantContext.TECHNICAL_TENANT_ID
            : filter.getParameterValue(TenantFilter.PARAMETER);
    // Primary-key reads need the same tenant check as the scoped JPQL query they replace.
    return FreshRowLock.lock(entityManager, Consultant.class, consultantId)
        .filter(consultant -> consultant.getDeleteDate() == null)
        .filter(
            consultant ->
                TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)
                    || (tenantId != null && tenantId.equals(consultant.getTenantId())));
  }
}
