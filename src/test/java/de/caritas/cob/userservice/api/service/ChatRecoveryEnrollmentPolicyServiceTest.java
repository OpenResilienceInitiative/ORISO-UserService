package de.caritas.cob.userservice.api.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.tenantservice.generated.web.model.*;
import org.junit.jupiter.api.Test;

class ChatRecoveryEnrollmentPolicyServiceTest {
  private final TenantService tenants = mock(TenantService.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final ChatRecoveryEnrollmentPolicyService service =
      new ChatRecoveryEnrollmentPolicyService(tenants, users, consultants);

  @Test
  void singleTenantCreationUsesAuthoritativeUncachedEndpointForBothRoles() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "multitenancyEnabled", false);
    var policy =
        new ChatRecoverySettings()
            .asker(ChatRecoveryMode.RECOVERY_KEY)
            .consultant(ChatRecoveryMode.LOGIN_PASSWORD)
            .revision(7L);
    when(tenants.getSingleTenancyTenantDataFresh())
        .thenReturn(
            new RestrictedTenantDTO()
                .id(42L)
                .settings(
                    new Settings()
                        .tenantAdminControls(
                            new TenantAdminControls().chatRecoverySettings(policy))));
    assertEquals(
        new ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot("LOGIN_PASSWORD", 7),
        service.forNewConsultant(null));
    assertEquals("RECOVERY_KEY", service.forNewAsker(null).mode());
    verify(tenants, times(2)).getSingleTenancyTenantDataFresh();
    verify(tenants, never()).getRestrictedTenantDataFresh(any());
  }

  @Test
  void missingSingleTenantPolicyFailsClosed() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "multitenancyEnabled", false);
    var exception =
        assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException.class,
            () -> service.forNewConsultant(null));
    assertEquals(org.springframework.http.HttpStatus.BAD_GATEWAY, exception.getHttpStatus());
    verify(tenants).getSingleTenancyTenantDataFresh();
  }

  @Test
  void multitenantNullAndTechnicalTenantNeverUseSingleTenantFallback() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "multitenancyEnabled", true);
    for (Long tenantId : new Long[] {null, 0L, -1L}) {
      assertThrows(
          de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException
              .class,
          () -> service.forNewConsultant(tenantId));
    }
    verifyNoInteractions(tenants);
  }

  @Test
  void roleGrantRetainsEnrolledIdentity() {
    var user = new User();
    user.setChatRecoveryMode("LOGIN_PASSWORD");
    user.setChatRecoveryPolicyRevision(2L);
    when(users.findById("existing")).thenReturn(java.util.Optional.of(user));
    assertEquals(
        new ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot("LOGIN_PASSWORD", 2),
        service.forExistingIdentity("existing"));
    verifyNoInteractions(tenants);
  }

  @Test
  void legacyIdentityNeverConsultsCurrentPolicy() {
    var user = new User();
    when(users.findById("old")).thenReturn(java.util.Optional.of(user));
    assertEquals(
        new ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot("RECOVERY_KEY", 0),
        service.forExistingIdentity("old"));
    verifyNoInteractions(tenants);
  }

  @Test
  void unavailablePolicyFailsClosed() {
    var exception =
        assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException.class,
            () -> service.forNewAsker(1L));
    assertEquals(org.springframework.http.HttpStatus.BAD_GATEWAY, exception.getHttpStatus());
  }

  @Test
  void snapshotsSurviveLaterPolicyChanges() {
    var controls =
        new TenantAdminControls()
            .chatRecoverySettings(
                new ChatRecoverySettings()
                    .asker(ChatRecoveryMode.LOGIN_PASSWORD)
                    .consultant(ChatRecoveryMode.RECOVERY_KEY)
                    .revision(4L));
    var tenant = new RestrictedTenantDTO().settings(new Settings().tenantAdminControls(controls));
    when(tenants.getRestrictedTenantDataFresh(1L)).thenReturn(tenant);
    var snapshot = service.forNewAsker(1L);
    assertEquals("LOGIN_PASSWORD", snapshot.mode());
    assertEquals("RECOVERY_KEY", service.forNewConsultant(1L).mode());
    controls.getChatRecoverySettings().asker(ChatRecoveryMode.RECOVERY_KEY).revision(5L);
    assertEquals("LOGIN_PASSWORD", snapshot.mode());
    assertEquals(4L, snapshot.revision());
    assertEquals("RECOVERY_KEY", service.forNewAsker(1L).mode());
  }
}
