package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

/** Existing mapped typed route must select C explicitly; absence never selects legacy creation. */
class ProtectedAdminOnboardingControllerTest {
  @Test
  void enabledProtectedRoleWithMissingCoordinatorCannotRunTheLegacyWholeFlow() {
    var legacy = mock(TenantAdminOnboardingService.class);
    var invites = mock(AccountInviteService.class);
    when(invites.findTargetRoleByToken("synthetic")).thenReturn(AccountInviteTargetRole.TENANT_ADMIN);
    when(legacy.registerTenantAdmin(eq("synthetic"),any())).thenReturn(new TenantAdminOnboardingService.TenantAdminRegistrationResult(7L,"synthetic",null));
    var controller = new TenantAdminOnboardingController(legacy,mock(CounsellorOnboardingService.class),invites);
    var actors = mock(TaskActorConfiguration.class); when(actors.isEnabled()).thenReturn(true);
    @SuppressWarnings("unchecked") ObjectProvider<ProtectedAdminOnboardingService> provider = mock(ObjectProvider.class);
    controller.protectedOnboarding(actors,provider,new MockHttpServletRequest());
    assertThrows(RuntimeException.class,() -> controller.registerTenantAdmin("synthetic",new TenantAdminOnboardingController.TenantAdminRegistrationRequestDTO()));
    verifyNoInteractions(legacy);
  }

  @Test
  void enabledPlatformRouteUsesProtectedCoordinatorAndTheOriginalRequestBytes() {
    var legacy = mock(TenantAdminOnboardingService.class); var invites = mock(AccountInviteService.class);
    when(invites.findTargetRoleByToken("synthetic")).thenReturn(AccountInviteTargetRole.PLATFORM_ADMIN);
    when(legacy.registerTenantAdmin(eq("synthetic"),any())).thenReturn(new TenantAdminOnboardingService.TenantAdminRegistrationResult(7L,"legacy-result",null));
    var coordinator = mock(ProtectedAdminOnboardingService.class);
    when(coordinator.register(eq("synthetic"),any(),any())).thenReturn(new TenantAdminOnboardingService.TenantAdminRegistrationResult(0L,"protected-result",null));
    var controller = new TenantAdminOnboardingController(legacy,mock(CounsellorOnboardingService.class),invites);
    var actors = mock(TaskActorConfiguration.class); when(actors.isEnabled()).thenReturn(true);
    @SuppressWarnings("unchecked") ObjectProvider<ProtectedAdminOnboardingService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(coordinator);
    var request = new MockHttpServletRequest("POST","/users/account-invites/synthetic/onboarding/register");
    request.addHeader("Idempotency-Key","7e1d58df-f9d3-4d2a-a6ea-7c7765e067f9");
    byte[] raw = "{ \"account\":{\"password\":\"synthetic only\"},\"unknown\":1.00 }".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    request.setAttribute(ProtectedAdminRegistrationBodyAdvice.ATTRIBUTE,raw);
    controller.protectedOnboarding(actors,provider,request);
    var result = assertDoesNotThrow(() -> controller.registerTenantAdmin("synthetic",new TenantAdminOnboardingController.TenantAdminRegistrationRequestDTO()));
    assertEquals(0L,result.getBody().tenantId); assertEquals("protected-result",result.getBody().twoFactor.secret);
    var exact = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
    verify(coordinator).register(eq("synthetic"),any(),exact.capture());
    assertEquals(new String(raw,java.nio.charset.StandardCharsets.UTF_8),exact.getValue().path("body").asText());
    assertEquals(request.getRequestURI(),exact.getValue().path("path").asText());
    verifyNoInteractions(legacy);
  }
}
