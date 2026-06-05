package de.caritas.cob.userservice.api.admin.service.admin.create;

import static de.caritas.cob.userservice.api.config.auth.UserRole.AGENCY_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.SINGLE_TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.TOPIC_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.UserRole.USER_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CreateAdminServiceTest {

  @InjectMocks private CreateAdminService createAdminService;

  @Mock private IdentityClient identityClient;

  @Mock private UserAccountInputValidator userAccountInputValidator;

  @Mock private UserHelper userHelper;

  @Mock private AdminRepository adminRepository;

  @Mock private AuthenticatedUser authenticatedUser;

  @Test
  void getDefaultRoles_Should_NotAssignLegacySingleTenantAdmin_ForSingleDomainTenantAdmin() {
    ReflectionTestUtils.setField(createAdminService, "multitenancyWithSingleDomain", true);

    List<UserRole> defaultRoles = createAdminService.getDefaultRoles(Admin.AdminType.TENANT);

    assertThat(defaultRoles).containsOnly(USER_ADMIN, AGENCY_ADMIN, TENANT_ADMIN);
    assertThat(defaultRoles).doesNotContain(SINGLE_TENANT_ADMIN);
  }

  @Test
  void getDefaultRoles_Should_NotAssignLegacySingleTenantAdmin_ForMultidomainTenantAdmin() {
    ReflectionTestUtils.setField(createAdminService, "multitenancyWithSingleDomain", false);

    List<UserRole> defaultRoles = createAdminService.getDefaultRoles(Admin.AdminType.TENANT);

    assertThat(defaultRoles).containsOnly(USER_ADMIN, AGENCY_ADMIN, TENANT_ADMIN, TOPIC_ADMIN);
    assertThat(defaultRoles).doesNotContain(SINGLE_TENANT_ADMIN);
  }
}
