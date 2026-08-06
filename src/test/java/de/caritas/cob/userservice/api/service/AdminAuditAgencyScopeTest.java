package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAuditAgencyScopeTest {

  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private AdminAgencyRepository adminAgencyRepository;

  @InjectMocks private AdminAuditAgencyScope scope;

  @Test
  void resolveAgencyIds_Should_ReturnEmpty_When_UserIsTenantSuperAdmin() {
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(true);

    assertThat(scope.resolveAgencyIds()).isEmpty();
  }

  @Test
  void resolveAgencyIds_Should_ReturnEmpty_When_UserIsSingleTenantAdmin() {
    when(authenticatedUser.isSingleTenantAdmin()).thenReturn(true);

    assertThat(scope.resolveAgencyIds()).isEmpty();
  }

  @Test
  void resolveAgencyIds_Should_ReturnEmpty_When_UserIsFullAgencyAdmin() {
    // `agency-admin` lifts the restriction — hasRestrictedAgencyPriviliges() is then false.
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(false);

    assertThat(scope.resolveAgencyIds()).isEmpty();
  }

  @Test
  void resolveAgencyIds_Should_ReturnAssignedAgencies_When_UserIsBeratungsstellenAdmin() {
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(adminAgencyRepository.findByAdminId("admin-1"))
        .thenReturn(
            List.of(
                AdminAgency.builder().agencyId(3L).build(),
                AdminAgency.builder().agencyId(8L).build()));

    assertThat(scope.resolveAgencyIds()).contains(Set.of(3L, 8L));
  }

  @Test
  void resolveAgencyIds_Should_SkipNullAgencyIds() {
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(adminAgencyRepository.findByAdminId("admin-1"))
        .thenReturn(
            List.of(
                AdminAgency.builder().agencyId(null).build(),
                AdminAgency.builder().agencyId(4L).build()));

    assertThat(scope.resolveAgencyIds()).contains(Set.of(4L));
  }

  @Test
  void resolveAgencyIds_Should_ReturnEmptySet_When_AdminHasNoAgencyAssignment() {
    // Fail closed: an empty set is a scope of nothing, not the absence of a scope.
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(adminAgencyRepository.findByAdminId("admin-1")).thenReturn(List.of());

    assertThat(scope.resolveAgencyIds()).isPresent();
    assertThat(scope.resolveAgencyIds()).contains(Set.of());
  }
}
