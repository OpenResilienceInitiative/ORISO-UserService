package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.controller.TenantAdminOnboardingController;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Actual MVC body conversion; data capture must preserve bytes the typed DTO discards. */
class ProtectedAdminRegistrationBodyAdviceTest {
  @Test
  void actualRegisterConverterPreservesRawOrderWhitespaceUnknownFieldsAndNumberRepresentation()
      throws Exception {
    var seen = new AtomicReference<byte[]>();
    var legacy = mock(TenantAdminOnboardingService.class);
    var invites = mock(AccountInviteService.class);
    when(invites.findTargetRoleByToken("synthetic-link"))
        .thenReturn(AccountInviteTargetRole.TENANT_ADMIN);
    when(legacy.registerTenantAdmin(eq("synthetic-link"), any()))
        .thenAnswer(
            call -> {
              var current =
                  (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
              Object captured =
                  current.getRequest().getAttribute(ProtectedAdminRegistrationBodyAdvice.ATTRIBUTE);
              if (captured instanceof byte[] bytes) seen.set(bytes.clone());
              return new TenantAdminOnboardingService.TenantAdminRegistrationResult(
                  7L, "synthetic-secret", null);
            });
    var controller =
        new TenantAdminOnboardingController(
            legacy, mock(CounsellorOnboardingService.class), invites);
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ProtectedAdminRegistrationBodyAdvice())
            .setMessageConverters(
                new MappingJackson2HttpMessageConverter(
                    new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
            .build();
    String original =
        "{ \"unknown\": {\"number\":1.00}, \"account\":{\"password\":\"synthetic only\"},\"reservedTenantId\":7 }";
    mvc.perform(
            post("/users/account-invites/synthetic-link/onboarding/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(original))
        .andExpect(status().isOk());
    assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), seen.get());
    verify(legacy).registerTenantAdmin(eq("synthetic-link"), any());
  }

  @Test
  void anotherMappedDtoRouteDoesNotCaptureARegistrationBody() throws Exception {
    var method =
        TenantAdminOnboardingController.class.getMethod(
            "activateTwoFactor",
            String.class,
            TenantAdminOnboardingController.TwoFactorActivationRequestDTO.class);
    var parameter = new org.springframework.core.MethodParameter(method, 1);
    assertFalse(
        new ProtectedAdminRegistrationBodyAdvice()
            .supports(
                parameter,
                parameter.getGenericParameterType(),
                MappingJackson2HttpMessageConverter.class));
  }

  @Test
  void registerAdviceIsSelectedOnlyForTheExistingTypedRegisterParameter() throws Exception {
    var method =
        TenantAdminOnboardingController.class.getMethod(
            "registerTenantAdmin",
            String.class,
            TenantAdminOnboardingController.TenantAdminRegistrationRequestDTO.class);
    var parameter = new org.springframework.core.MethodParameter(method, 1);
    assertTrue(
        new ProtectedAdminRegistrationBodyAdvice()
            .supports(
                parameter,
                parameter.getGenericParameterType(),
                MappingJackson2HttpMessageConverter.class));
  }
}
