package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Template decisions need the caller's kind and Träger only, never their agencies. */
@ExtendWith(MockitoExtension.class)
class AccountInviteAccessPolicyTemplateScopeTest {

  private static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;

  @Mock private AdminRepository adminRepository;
  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private UserRepository userRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserAgencyRepository userAgencyRepository;
  @Mock private AgencyService agencyService;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private AccountInviteAccessPolicy policy;

  @BeforeEach
  void setUp() {
    var adminScope =
        new AdminScope(
            caller,
            adminRepository,
            adminAgencyRepository,
            consultantRepository,
            consultantAgencyRepository,
            agencyService,
            userRepository,
            sessionRepository,
            userAgencyRepository);
    ReflectionTestUtils.setField(adminScope, "multitenancyEnabled", true);
    policy = new AccountInviteAccessPolicy(caller, adminScope);
    caller.setUserId("caller");
  }

  @Test
  void templateDecisions_Should_NotLoadAgencies_When_AgencyAdmin() {
    actAs(OWN_TENANT, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);

    assertThat(policy.templateOwnerTenantId()).isEqualTo(OWN_TENANT);
    assertThat(policy.seesEveryTemplate()).isFalse();
    assertThat(policy.canUseTemplate(OWN_TENANT)).isTrue();
    assertThat(policy.canUseTemplate(null)).isTrue();
    assertThat(policy.canUseTemplate(FOREIGN_TENANT)).isFalse();
    assertThat(policy.canChangeTemplate(OWN_TENANT)).isTrue();
    assertThat(policy.canChangeTemplate(null)).isFalse();
    assertThat(policy.canChangeTemplate(FOREIGN_TENANT)).isFalse();

    verifyNoInteractions(adminAgencyRepository, agencyService);
  }

  @Test
  void templateDecisions_Should_KeepTheKindRules_When_TraegerOrPlatformAdmin() {
    actAs(OWN_TENANT, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
    assertThat(policy.seesEveryTemplate()).isFalse();
    assertThat(policy.canChangeTemplate(FOREIGN_TENANT)).isFalse();

    actAs(0L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
    assertThat(policy.seesEveryTemplate()).isTrue();
    assertThat(policy.templateOwnerTenantId()).isNull();
    assertThat(policy.canChangeTemplate(null)).isTrue();
    assertThat(policy.canUseTemplate(FOREIGN_TENANT)).isTrue();
  }

  @Test
  void templateDecisions_Should_Refuse_When_TenantZeroCallerLacksPlatformAdminRoles() {
    actAs(0L, UserRole.USER_ADMIN);

    assertThatThrownBy(() -> policy.seesEveryTemplate()).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> policy.templateOwnerTenantId()).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> policy.canChangeTemplate(null)).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void templateDecisions_Should_BeUnrestricted_When_CallerHasNoTenantOnSingleTenantDeployment() {
    actAs(null, UserRole.USER_ADMIN);

    assertThat(policy.seesEveryTemplate()).isTrue();
  }

  @Test
  void templateDecisions_Should_BeUnrestricted_When_TechnicalUser() {
    actAs(0L, UserRole.TECHNICAL);

    assertThat(policy.seesEveryTemplate()).isTrue();
  }

  private void actAs(Long tenantId, UserRole... roles) {
    caller.setTenantId(tenantId);
    caller.setRoles(Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
  }
}
