package de.caritas.cob.userservice.api.admin.service.admin.search;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class RetrieveAdminServiceTest {

  @InjectMocks private RetrieveAdminService retrieveAdminService;

  @Mock private AdminRepository adminRepository;

  @Mock private AdminAgencyRepository adminAgencyRepository;

  /**
   * #968 fail-closed contract: a null caller tenant id must NOT be forwarded to
   * AdminRepository#findAllByInfixAndTenantId (which would return an unbounded result set), and the
   * method must return an empty page instead. Same shape as {@link
   * RetrieveAdminService#findAllByInfixScopedToAgencies} on an empty agency set.
   */
  @Test
  void findAllByInfixScopedToTenant_Should_ReturnEmpty_AndSkipRepository_WhenTenantIdIsNull() {
    PageRequest pageRequest = PageRequest.of(0, 10);

    var result =
        retrieveAdminService.findAllByInfixScopedToTenant(
            "*", Admin.AdminType.TENANT, null, pageRequest);

    assertThat(result.getContent()).isEmpty();
    assertThat(result.getTotalElements()).isZero();
    Mockito.verifyNoInteractions(adminRepository);
  }

  @Test
  void findAllByInfixScopedToTenant_Should_DelegateToRepository_WhenTenantIdIsPresent() {
    PageRequest pageRequest = PageRequest.of(0, 10);
    Mockito.when(
            adminRepository.findAllByInfixAndTenantId("*", Admin.AdminType.AGENCY, 9L, pageRequest))
        .thenReturn(org.springframework.data.domain.Page.empty(pageRequest));

    retrieveAdminService.findAllByInfixScopedToTenant("*", Admin.AdminType.AGENCY, 9L, pageRequest);

    Mockito.verify(adminRepository)
        .findAllByInfixAndTenantId("*", Admin.AdminType.AGENCY, 9L, pageRequest);
  }
}
