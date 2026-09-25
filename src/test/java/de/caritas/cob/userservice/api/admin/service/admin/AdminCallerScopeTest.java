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
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
  @Mock private UserRepository userRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserAgencyRepository userAgencyRepository;

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
            agencyService,
            userRepository,
            sessionRepository,
            userAgencyRepository);
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

  @Test
  void
      assertMayActOnConsultant_Should_Refuse_When_DeletedCounsellorLeftCallersAgencyBeforeDeletion() {
    givenDeletedCounsellorWhoLeftAgency10AndWasDeletedFromAgency11();
    actAsAgencyAdminOf(10L);

    assertThatThrownBy(() -> adminCallerScope.assertMayActOnConsultant("deleted-counsellor"))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMayActOnConsultant_Should_Allow_When_DeletedCounsellorBelongedToCallersAgency() {
    givenDeletedCounsellorWhoLeftAgency10AndWasDeletedFromAgency11();
    actAsAgencyAdminOf(11L);

    assertThatCode(() -> adminCallerScope.assertMayActOnConsultant("deleted-counsellor"))
        .doesNotThrowAnyException();
  }

  /** The deletion stamps the relations it removes with the counsellor's own delete date. */
  private void givenDeletedCounsellorWhoLeftAgency10AndWasDeletedFromAgency11() {
    var deletedAt = LocalDateTime.of(2026, 9, 25, 10, 0);
    var counsellor = new Consultant();
    counsellor.setId("deleted-counsellor");
    counsellor.setTenantId(OWN_TENANT);
    counsellor.setDeleteDate(deletedAt);
    when(consultantRepository.findById("deleted-counsellor")).thenReturn(Optional.of(counsellor));
    when(consultantAgencyRepository.findByConsultantId("deleted-counsellor"))
        .thenReturn(
            List.of(
                relation(counsellor, 10L, deletedAt.minusDays(30)),
                relation(counsellor, 11L, deletedAt)));
  }

  private static ConsultantAgency relation(Consultant counsellor, long agencyId, LocalDateTime at) {
    var relation = new ConsultantAgency();
    relation.setConsultant(counsellor);
    relation.setAgencyId(agencyId);
    relation.setDeleteDate(at);
    return relation;
  }

  private void actAsAgencyAdminOf(long agencyId) {
    actAs(OWN_TENANT, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
    when(adminAgencyRepository.findByAdminId(caller.getUserId()))
        .thenReturn(List.of(AdminAgency.builder().agencyId(agencyId).build()));
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
