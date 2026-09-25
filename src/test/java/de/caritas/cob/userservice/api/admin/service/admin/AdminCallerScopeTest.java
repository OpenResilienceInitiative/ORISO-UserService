package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminCallerScopeTest {

  private static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;

  @Mock private AdminRepository adminRepository;
  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private AgencyService agencyService;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private AdminCallerScope adminCallerScope;

  @BeforeEach
  void traegerAdminOfOwnTenant() {
    adminCallerScope =
        new AdminCallerScope(
            caller,
            adminRepository,
            adminAgencyRepository,
            consultantRepository,
            consultantAgencyRepository,
            agencyService);
    caller.setUserId("traeger-admin");
    caller.setTenantId(OWN_TENANT);
    caller.setRoles(
        Set.of(
            UserRole.TENANT_ADMIN.getValue(),
            UserRole.AGENCY_ADMIN.getValue(),
            UserRole.USER_ADMIN.getValue()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void assertMayUseAgencies_Should_ResolveAllAgenciesInOneCall_When_TraegerAdmin() {
    givenAgencies(agency(10L, OWN_TENANT), agency(11L, OWN_TENANT));

    assertThatCode(() -> adminCallerScope.assertMayUseAgencies(List.of(10L, 11L)))
        .doesNotThrowAnyException();

    ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
    verify(agencyService, times(1)).getAgenciesWithoutCaching(ids.capture());
    assertThat(ids.getValue()).containsExactlyInAnyOrder(10L, 11L);
    verify(agencyService, never()).getAgencyWithoutCaching(any());
  }

  @Test
  void assertMayUseAgencies_Should_Refuse_When_OneAgencyBelongsToAnotherTenant() {
    givenAgencies(agency(10L, OWN_TENANT), agency(20L, FOREIGN_TENANT));

    assertThatThrownBy(() -> adminCallerScope.assertMayUseAgencies(List.of(10L, 20L)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMayUseAgencies_Should_Refuse_When_AgencyServiceDoesNotKnowOneAgency() {
    givenAgencies(agency(10L, OWN_TENANT));

    assertThatThrownBy(() -> adminCallerScope.assertMayUseAgencies(List.of(10L, 99L)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMayActOnAdmin_Should_Refuse_When_TenantZeroCallerLacksPlatformAdminRoles() {
    actAs(0L, UserRole.USER_ADMIN);

    assertThatThrownBy(() -> adminCallerScope.assertMayActOnAdmin(admin(FOREIGN_TENANT)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMayUseAgencies_Should_Refuse_When_TenantZeroCallerLacksPlatformAdminRoles() {
    actAs(0L, UserRole.USER_ADMIN);
    givenAgencies(agency(20L, FOREIGN_TENANT));

    assertThatThrownBy(() -> adminCallerScope.assertMayUseAgencies(List.of(20L)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMayActOnAdmin_Should_Allow_When_PlatformAdmin() {
    actAs(0L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);

    assertThatCode(() -> adminCallerScope.assertMayActOnAdmin(admin(FOREIGN_TENANT)))
        .doesNotThrowAnyException();
  }

  @Test
  void assertMayActOnAdmin_Should_Allow_When_CallerHasNoTenantOnSingleTenantDeployment() {
    actAs(null, UserRole.USER_ADMIN);

    assertThatCode(() -> adminCallerScope.assertMayActOnAdmin(admin(FOREIGN_TENANT)))
        .doesNotThrowAnyException();
  }

  private void actAs(Long tenantId, UserRole... roles) {
    caller.setTenantId(tenantId);
    caller.setRoles(Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
  }

  private static Admin admin(long tenantId) {
    return Admin.builder()
        .id("target-admin")
        .tenantId(tenantId)
        .username("target")
        .firstName("Target")
        .lastName("Admin")
        .email("target@synthetic.oriso.test")
        .build();
  }

  private void givenAgencies(AgencyDTO... agencies) {
    when(agencyService.getAgenciesWithoutCaching(anyList())).thenReturn(List.of(agencies));
    for (AgencyDTO agency : agencies) {
      when(agencyService.getAgencyWithoutCaching(agency.getId())).thenReturn(agency);
    }
  }

  private static AgencyDTO agency(long id, long tenantId) {
    return new AgencyDTO().id(id).tenantId(tenantId);
  }
}
