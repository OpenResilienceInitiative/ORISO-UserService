package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Agencies;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Platform;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Tenant;
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
    assertThatThrownBy(() -> adminScope.assertMay(Target.tenant(null)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> adminScope.assertMay(Target.tenant(5L)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> adminScope.assertMay(Target.tenant(0L)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMay_Should_AllowUnnamedTenant_OnlyForThePlatform() {
    actAs(0L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);

    assertThatCode(() -> adminScope.assertMay(Target.tenant(null))).doesNotThrowAnyException();
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
    verifyNoInteractions(agencyService);
  }

  @Test
  void current_Should_BeOwnAgencies_When_TechnicalUserIsAlsoAgencyAdmin() {
    actAs(4L, UserRole.TECHNICAL, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
    givenOwnAgencies(7L);

    assertThat(adminScope.current()).isEqualTo(new Agencies(4L, Set.of(7L)));
  }

  @Test
  void narrow_Should_Deny_When_Tenant0AdminIsNoPlatformAdmin() {
    actAs(0L, UserRole.USER_ADMIN);

    assertThatThrownBy(() -> adminScope.<Admin>narrow(null, (root, query, cb, ids) -> null))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMay_Should_DenyAdminWithoutSharedAgency_When_AgencyAdminOnSingleTenantDeployment() {
    ReflectionTestUtils.setField(adminScope, "multitenancyEnabled", false);
    actAs(null, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
    givenOwnAgencies(7L);
    when(adminRepository.findById("other")).thenReturn(Optional.of(admin("other", 5L)));
    when(adminAgencyRepository.findByAdminId("other"))
        .thenReturn(List.of(AdminAgency.builder().agencyId(8L).build()));

    assertThatThrownBy(() -> adminScope.assertMay(Target.admin("other")))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMay_Should_AllowOwnTenantCounsellorAccount_And_DenyForeignOne() {
    actAs(4L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
    givenCounsellor("own", 4L, null);
    givenCounsellor("foreign", 5L, null);

    assertThatCode(() -> adminScope.assertMay(Target.account("own"))).doesNotThrowAnyException();
    assertThatThrownBy(() -> adminScope.assertMay(Target.account("foreign")))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMay_Should_DenyDeletedCounsellor_When_ItLeftTheCallersAgencyBeforeDeletion() {
    actAs(4L, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
    givenOwnAgencies(10L);
    givenDeletedCounsellorWhoLeftAgency10AndWasDeletedFromAgency11();

    assertThatThrownBy(() -> adminScope.assertMay(Target.counsellor("deleted")))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMay_Should_AllowDeletedCounsellor_When_ItBelongedToTheCallersAgencyAtDeletion() {
    actAs(4L, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
    givenOwnAgencies(11L);
    givenDeletedCounsellorWhoLeftAgency10AndWasDeletedFromAgency11();

    assertThatCode(() -> adminScope.assertMay(Target.counsellor("deleted")))
        .doesNotThrowAnyException();
  }

  /** The deletion stamps the relations it removes with the counsellor's own delete date. */
  private void givenDeletedCounsellorWhoLeftAgency10AndWasDeletedFromAgency11() {
    var deletedAt = LocalDateTime.of(2026, 9, 25, 10, 0);
    var counsellor = givenCounsellor("deleted", 4L, deletedAt);
    when(consultantAgencyRepository.findByConsultantId("deleted"))
        .thenReturn(
            List.of(
                relation(counsellor, 10L, deletedAt.minusDays(30)),
                relation(counsellor, 11L, deletedAt)));
  }

  private Consultant givenCounsellor(String id, long tenantId, LocalDateTime deleteDate) {
    var counsellor = new Consultant();
    counsellor.setId(id);
    counsellor.setTenantId(tenantId);
    counsellor.setDeleteDate(deleteDate);
    when(consultantRepository.findById(id)).thenReturn(Optional.of(counsellor));
    return counsellor;
  }

  private static ConsultantAgency relation(Consultant counsellor, long agencyId, LocalDateTime at) {
    var relation = new ConsultantAgency();
    relation.setConsultant(counsellor);
    relation.setAgencyId(agencyId);
    relation.setDeleteDate(at);
    return relation;
  }

  private static Admin admin(String id, long tenantId) {
    return Admin.builder()
        .id(id)
        .tenantId(tenantId)
        .username(id)
        .firstName("F")
        .lastName("A")
        .email(id + "@synthetic.oriso.test")
        .type(Admin.AdminType.AGENCY)
        .build();
  }

  @Test
  @SuppressWarnings("unchecked")
  void assertMay_Should_ResolveAllAgenciesInOneCall_When_TraegerAdmin() {
    actAs(4L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
    givenAgencies(agency(10L, 4L), agency(11L, 4L));

    assertThatCode(() -> adminScope.assertMay(Target.agencies(List.of(10L, 11L))))
        .doesNotThrowAnyException();

    ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
    verify(agencyService, times(1)).getAgenciesWithoutCaching(ids.capture());
    assertThat(ids.getValue()).containsExactlyInAnyOrder(10L, 11L);
    verify(agencyService, never()).getAgencyWithoutCaching(any());
  }

  @Test
  void assertMay_Should_DenyAgencies_When_OneBelongsToAnotherTenantOrIsUnknown() {
    actAs(4L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
    givenAgencies(agency(10L, 4L), agency(20L, 5L));

    assertThatThrownBy(() -> adminScope.assertMay(Target.agencies(List.of(10L, 20L))))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> adminScope.assertMay(Target.agencies(List.of(10L, 99L))))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMay_Should_LetAnOrphanedAgencyGo_But_KeepForeignOnesOut_When_RelationsAreRemoved() {
    actAs(4L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
    givenAgencies(agency(10L, 4L), agency(20L, 5L));

    assertThatCode(() -> adminScope.assertMay(Target.removedAgencies(List.of(10L, 99L))))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> adminScope.assertMay(Target.removedAgencies(List.of(10L, 20L))))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void assertMay_Should_KeepAgencyAdminToOwnAgencies_When_RelationsAreRemoved() {
    actAs(4L, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
    givenOwnAgencies(10L);

    assertThatCode(() -> adminScope.assertMay(Target.removedAgencies(List.of(10L))))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> adminScope.assertMay(Target.removedAgencies(List.of(99L))))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(agencyService);
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

  private void givenAgencies(AgencyDTO... agencies) {
    when(agencyService.getAgenciesWithoutCaching(anyList()))
        .thenAnswer(
            call ->
                Arrays.stream(agencies)
                    .filter(agency -> ((List<?>) call.getArgument(0)).contains(agency.getId()))
                    .toList());
    for (AgencyDTO agency : agencies) {
      when(agencyService.getAgencyWithoutCaching(agency.getId())).thenReturn(agency);
    }
  }

  private static AgencyDTO agency(long id, long tenantId) {
    return new AgencyDTO().id(id).tenantId(tenantId);
  }

  private void givenOwnAgencies(Long... agencyIds) {
    when(adminAgencyRepository.findByAdminId(CALLER))
        .thenReturn(
            Arrays.stream(agencyIds)
                .map(agencyId -> AdminAgency.builder().agencyId(agencyId).build())
                .toList());
  }
}
