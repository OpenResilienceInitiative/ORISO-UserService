package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminTenantOwnershipValidatorTest {

  @Mock private AuthenticatedUser authenticatedUser;

  @Test
  void
      assertCallerMayCreateAdminForTenant_Should_Throw_When_TenantScopedCallerTargetsOtherTenant() {
    lenient().when(authenticatedUser.getUserId()).thenReturn("caller-id");
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.getTenantId()).thenReturn(9L);

    assertThatThrownBy(
            () ->
                AdminTenantOwnershipValidator.assertCallerMayCreateAdminForTenant(
                    authenticatedUser, 7))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage(AdminTenantOwnershipValidator.CROSS_TENANT_MESSAGE);
  }

  @Test
  void assertCallerMayCreateAdminForTenant_Should_Pass_When_TenantScopedCallerTargetsOwnTenant() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.getTenantId()).thenReturn(9L);

    assertThatCode(
            () ->
                AdminTenantOwnershipValidator.assertCallerMayCreateAdminForTenant(
                    authenticatedUser, 9))
        .doesNotThrowAnyException();
  }

  @Test
  void assertCallerMayCreateAdminForTenant_Should_Pass_When_CallerIsPlatformAdmin() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);

    assertThatCode(
            () ->
                AdminTenantOwnershipValidator.assertCallerMayCreateAdminForTenant(
                    authenticatedUser, 7))
        .doesNotThrowAnyException();
  }

  @Test
  void assertCallerMayCreateAdminForTenant_Should_Pass_When_CallerIsPlatformAdminCreatingTenant0() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);

    assertThatCode(
            () ->
                AdminTenantOwnershipValidator.assertCallerMayCreateAdminForTenant(
                    authenticatedUser, 0))
        .doesNotThrowAnyException();
  }

  /**
   * Tenant 0 is the technical tenant nobody belongs to, so a caller in that context is not bounded
   * by this check — provisioning a tenant's admins from the platform context is established
   * behaviour (see {@code CreateAdminServiceIT}, {@code UserAdminControllerE2EIT}).
   */
  @Test
  void assertCallerMayCreateAdminForTenant_Should_Pass_When_CallerRunsInTechnicalTenantContext() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.getTenantId()).thenReturn(0L);

    assertThatCode(
            () ->
                AdminTenantOwnershipValidator.assertCallerMayCreateAdminForTenant(
                    authenticatedUser, 7))
        .doesNotThrowAnyException();
  }

  @Test
  void assertCallerMayCreateAdminForTenant_Should_Pass_When_CallerHasNoTenant() {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(false);
    when(authenticatedUser.getTenantId()).thenReturn(null);

    assertThatCode(
            () ->
                AdminTenantOwnershipValidator.assertCallerMayCreateAdminForTenant(
                    authenticatedUser, 7))
        .doesNotThrowAnyException();
  }

  @Test
  void assertCallerMayCreateAdminForTenant_Should_Pass_When_NoTenantIdWasRequested() {
    assertThatCode(
            () ->
                AdminTenantOwnershipValidator.assertCallerMayCreateAdminForTenant(
                    authenticatedUser, null))
        .doesNotThrowAnyException();
  }
}
