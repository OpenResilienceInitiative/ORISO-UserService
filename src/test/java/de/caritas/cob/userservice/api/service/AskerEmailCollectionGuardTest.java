package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AskerEmailCollectionGuardTest {
  @Mock UserRepository users;
  @Mock AuthenticatedUser authenticatedUser;
  @Mock TenantService tenants;
  @Mock IdentitySecondFactor secondFactor;
  @InjectMocks AskerEmailCollectionGuard guard;

  @BeforeEach
  void actualAsker() {
    var user = new User();
    user.setUserId("asker");
    user.setTenantId(42L);
    user.setEmail("existing@example.org");
    when(authenticatedUser.getUserId()).thenReturn("asker");
    when(users.findByUserIdAndDeleteDateIsNull("asker")).thenReturn(Optional.of(user));
  }

  @Test
  void disabledCollectionRefusesNewTargetBeforeMail() {
    disabled();
    assertThatThrownBy(() -> guard.beforeStart("new@example.org"))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(secondFactor);
  }

  @Test
  void existingSameAddressDoesNotCollectAnything() {
    guard.beforeStart("EXISTING@example.org");
    verifyNoInteractions(tenants, secondFactor);
  }

  @Test
  void disablingCollectionDuringEnrollmentStopsPendingDifferentAddress() {
    disabled();
    when(secondFactor.emailVerificationAddresses("asker")).thenReturn(List.of("new@example.org"));
    assertThatThrownBy(guard::beforeFinish).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void existingSameAddressMayFinishWithoutCollectingANewAddress() {
    disabled();
    when(secondFactor.emailVerificationAddresses("asker"))
        .thenReturn(List.of("EXISTING@example.org"));
    guard.beforeFinish();
  }

  @Test
  void unknownOrMixedPendingTargetsFailClosed() {
    disabled();
    when(secondFactor.emailVerificationAddresses("asker")).thenReturn(List.of());
    assertThatThrownBy(guard::beforeFinish).isInstanceOf(ForbiddenException.class);
    when(secondFactor.emailVerificationAddresses("asker"))
        .thenReturn(List.of("existing@example.org", "other@example.org"));
    assertThatThrownBy(guard::beforeFinish).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void enabledCollectionDoesNotNeedProviderMetadata() {
    when(tenants.getRestrictedTenantDataFresh(42L)).thenReturn(tenant(true));
    guard.beforeFinish();
    verifyNoInteractions(secondFactor);
  }

  @Test
  void policyOutageAndMismatchedTenantFailClosed() {
    when(tenants.getRestrictedTenantDataFresh(42L)).thenThrow(new IllegalStateException("offline"));
    assertThatThrownBy(() -> guard.beforeStart("new@example.org"))
        .isInstanceOf(ServiceUnavailableException.class);
    doReturn(tenant(true).id(99L)).when(tenants).getRestrictedTenantDataFresh(42L);
    assertThatThrownBy(() -> guard.beforeStart("new@example.org"))
        .isInstanceOf(ServiceUnavailableException.class);
    verifyNoInteractions(secondFactor);
  }

  @Test
  void aMissingAnonymousAskerRowCannotBypassCollectionChecks() {
    when(users.findByUserIdAndDeleteDateIsNull("asker")).thenReturn(Optional.empty());
    when(authenticatedUser.getRoles())
        .thenReturn(
            java.util.Set.of(
                de.caritas.cob.userservice.api.config.auth.UserRole.ANONYMOUS.getValue()));
    assertThatThrownBy(() -> guard.beforeStart("new@example.org"))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThatThrownBy(guard::beforeFinish).isInstanceOf(ServiceUnavailableException.class);
    verifyNoInteractions(tenants, secondFactor);
  }

  @Test
  void consultantWithoutAnAskerRowIsUnaffected() {
    when(users.findByUserIdAndDeleteDateIsNull("asker")).thenReturn(Optional.empty());
    guard.beforeStart("consultant@example.org");
    guard.beforeFinish();
    verifyNoInteractions(tenants, secondFactor);
  }

  @Test
  void legacySingleTenantOtpFinishUsesConfiguredPolicyBeforeProviderEffects() {
    var user = new User();
    user.setUserId("asker");
    when(users.findByUserIdAndDeleteDateIsNull("asker")).thenReturn(Optional.of(user));
    when(tenants.getSingleTenancyTenantDataFresh()).thenReturn(tenant(true));
    guard.beforeFinish();
    verify(tenants).getSingleTenancyTenantDataFresh();
    verifyNoInteractions(secondFactor);
  }

  private void disabled() {
    when(tenants.getRestrictedTenantDataFresh(42L)).thenReturn(tenant(false));
  }

  private RestrictedTenantDTO tenant(boolean enabled) {
    return new RestrictedTenantDTO()
        .id(42L)
        .settings(new Settings().featureAskerEmailEnabled(enabled));
  }
}
