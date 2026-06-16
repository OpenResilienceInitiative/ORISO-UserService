package de.caritas.cob.userservice.api.admin.service.consultant;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantFilter;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import lombok.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/** Service class to provide filtered search for all {@link Consultant} entities based on tenant. */
@Service
@Primary
@ConditionalOnExpression("${multitenancy.enabled:true}")
public class ConsultantAdminFilterTenantAwareService extends ConsultantAdminFilterService {

  protected static final String TENANT_ID_SEARCH_FIELD = "tenantId";

  public ConsultantAdminFilterTenantAwareService(
      @NonNull ConsultantRepository consultantRepository) {
    super(consultantRepository);
  }

  @Override
  protected Specification<Consultant> buildSpecification(ConsultantFilter consultantFilter) {
    var baseSpecification = super.buildSpecification(consultantFilter);
    if (TenantContext.isTechnicalOrSuperAdminContext()) {
      return baseSpecification;
    }
    var currentTenant = TenantContext.getCurrentTenant();
    return Specification.where(baseSpecification)
        .and((root, query, cb) -> cb.equal(root.get(TENANT_ID_SEARCH_FIELD), currentTenant));
  }
}
