package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Agencies;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Platform;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Tenant;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminScopeTest {

  private static final String CALLER = "caller";

  @Mock private AdminRepository adminRepository;
  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private AgencyService agencyService;
  @Mock private UserRepository userRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserAgencyRepository userAgencyRepository;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private AdminScope adminScope;

  @BeforeEach
  void multiTenantDeployment() {
    adminScope =
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
    caller.setUserId(CALLER);
  }

  @Test
  void current_Should_BePlatform_When_PlatformAdmin() {
    actAs(0L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);

    assertThat(adminScope.current()).isEqualTo(new Platform());
  }

  @Test
  void current_Should_BePlatform_When_TechnicalUser() {
    actAs(null, UserRole.TECHNICAL);

    assertThat(adminScope.current()).isEqualTo(new Platform());
  }

  @Test
  void current_Should_BeOwnTenant_When_TraegerAdmin() {
    actAs(4L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);

    assertThat(adminScope.current()).isEqualTo(new Tenant(4L));
  }

  @Test
  void current_Should_BeOwnAgencies_When_AgencyAdmin() {
    actAs(4L, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
    givenOwnAgencies(7L, null, 9L);

    assertThat(adminScope.current()).isEqualTo(new Agencies(4L, Set.of(7L, 9L)));
  }

  @Test
  void current_Should_Deny_When_Tenant0AdminIsNoPlatformAdmin() {
    actAs(0L, UserRole.TENANT_ADMIN, UserRole.USER_ADMIN);

    assertThatThrownBy(adminScope::current).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void current_Should_Deny_When_CallerHasNoTenant() {
    actAs(null, UserRole.TENANT_ADMIN, UserRole.USER_ADMIN);

    assertThatThrownBy(adminScope::current).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void current_Should_BePlatformOrAgencies_When_SingleTenantDeployment() {
    ReflectionTestUtils.setField(adminScope, "multitenancyEnabled", false);
    actAs(null, UserRole.USER_ADMIN);
    assertThat(adminScope.current()).isEqualTo(new Platform());

    actAs(null, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
    givenOwnAgencies(7L);
    assertThat(adminScope.current()).isEqualTo(new Agencies(null, Set.of(7L)));
  }

  @Test
  void assertMay_Should_BoundTenantTargets_ToOwnTenant() {
    actAs(4L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);

    assertThatCode(() -> adminScope.assertMay(Target.tenant(4L))).doesNotThrowAnyException();
    assertThatCode(() -> adminScope.assertMay(Target.tenant(null))).doesNotThrowAnyException();
    assertThatThrownBy(() -> adminScope.assertMay(Target.tenant(5L)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> adminScope.assertMay(Target.tenant(0L)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMay_Should_DenyTenantTargets_When_AgencyAdmin() {
    actAs(4L, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);

    assertThatThrownBy(() -> adminScope.assertMay(Target.tenant(4L)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMay_Should_KeepAgencyAdmin_InOwnAgencies() {
    actAs(4L, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
    givenOwnAgencies(7L);

    assertThatCode(() -> adminScope.assertMay(Target.agencies(List.of(7L))))
        .doesNotThrowAnyException();
    assertThatCode(() -> adminScope.assertMay(Target.placedIn(4L, 7L))).doesNotThrowAnyException();
    assertThatThrownBy(() -> adminScope.assertMay(Target.agencies(List.of(7L, 8L))))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> adminScope.assertMay(Target.placedIn(4L, null)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> adminScope.assertMay(Target.placedIn(5L, 7L)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMay_Should_DenyAdminOfAnotherTenant_And_PassUnknownAdmin() {
    actAs(4L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
    when(adminRepository.findById("foreign"))
        .thenReturn(
            Optional.of(
                Admin.builder()
                    .id("foreign")
                    .tenantId(5L)
                    .username("foreign")
                    .firstName("F")
                    .lastName("A")
                    .email("foreign@synthetic.oriso.test")
                    .type(Admin.AdminType.AGENCY)
                    .build()));
    when(adminRepository.findById("unknown")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> adminScope.assertMay(Target.admin("foreign")))
        .isInstanceOf(ForbiddenException.class);
    assertThatCode(() -> adminScope.assertMay(Target.admin("unknown"))).doesNotThrowAnyException();
  }

  @Test
  void assertMay_Should_DenyUnknownAccount() {
    actAs(4L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);

    assertThatThrownBy(() -> adminScope.assertMay(Target.account("unknown")))
        .isInstanceOf(ForbiddenException.class);
  }

  private void actAs(Long tenantId, UserRole... roles) {
    caller.setTenantId(tenantId);
    caller.setRoles(Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
  }

  private void givenOwnAgencies(Long... agencyIds) {
    when(adminAgencyRepository.findByAdminId(CALLER))
        .thenReturn(
            Arrays.stream(agencyIds)
                .map(agencyId -> AdminAgency.builder().agencyId(agencyId).build())
                .toList());
  }
}
