package de.caritas.cob.userservice.api.admin.service.consultant;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantFilter;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultantAdminFilterTenantAwareServiceTest {

  @Mock ConsultantRepository consultantRepository;

  @InjectMocks ConsultantAdminFilterTenantAwareService consultantAdminFilterTenantAwareService;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  Root<Consultant> root;

  @Mock CriteriaQuery<?> criteriaQuery;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  CriteriaBuilder criteriaBuilder;

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void buildSpecification_Should_NotAddTenantFilter_When_TenantContextIsSuperAdmin() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);

    var specification =
        consultantAdminFilterTenantAwareService.buildSpecification(
            new ConsultantFilter().agencyId(59L));
    specification.toPredicate(root, criteriaQuery, criteriaBuilder);

    verify(root, never())
        .get(ConsultantAdminFilterTenantAwareService.TENANT_ID_SEARCH_FIELD);
  }

  @Test
  void buildSpecification_Should_AddTenantFilter_When_TenantContextIsNotSuperAdmin() {
    TenantContext.setCurrentTenant(1L);

    var specification =
        consultantAdminFilterTenantAwareService.buildSpecification(
            new ConsultantFilter().agencyId(59L));
    specification.toPredicate(root, criteriaQuery, criteriaBuilder);

    verify(root).get(ConsultantAdminFilterTenantAwareService.TENANT_ID_SEARCH_FIELD);
  }
}
