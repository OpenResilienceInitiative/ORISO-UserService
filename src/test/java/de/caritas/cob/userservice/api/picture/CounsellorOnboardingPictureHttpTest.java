package de.caritas.cob.userservice.api.picture;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Issue #1049: the onboarding wizard's picture step runs without a session. The raw invite token is
 * the credential, and a token that has not registered yet buys nothing.
 */
@SpringJUnitWebConfig(CounsellorOnboardingPictureHttpTest.Config.class)
@TestPropertySource(properties = "multitenancy.enabled=false")
class CounsellorOnboardingPictureHttpTest {
  @Configuration
  @EnableWebMvc
  @Import({
    SecurityConfig.class,
    RoleAuthorizationAuthorityMapper.class,
    CounsellorOnboardingPictureController.class,
    ConsultantPictureAccess.class,
    ConsultantPictureService.class,
    PictureIntake.class,
    ApiResponseEntityExceptionHandler.class
  })
  static class Config {
    @Bean
    CsrfSecurityProperties csrfSecurityProperties() {
      var p = new CsrfSecurityProperties();
      var cookie = new CsrfSecurityProperties.ConfigProperty();
      cookie.setProperty("CSRF-TOKEN");
      p.setCookie(cookie);
      var header = new CsrfSecurityProperties.ConfigProperty();
      header.setProperty("X-CSRF-Token");
      p.setHeader(header);
      var whitelist = new CsrfSecurityProperties.Whitelist();
      var wh = new CsrfSecurityProperties.ConfigProperty();
      wh.setProperty("X-CSRF-Whitelist");
      whitelist.setHeader(wh);
      p.setWhitelist(whitelist);
      return p;
    }
  }

  @Autowired WebApplicationContext context;
  @MockitoBean JwtDecoder jwtDecoder;

  @MockitoBean
  de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService inactivity;

  @MockitoBean ConsultantPictureStore store;
  @MockitoBean ClamAvPictureScanner scanner;
  @MockitoBean AuthenticatedUser caller;
  @MockitoBean ConsultantRepository consultants;
  @MockitoBean AdminUserFacade admins;
  @MockitoBean ConsultantAgencyAdminService agencies;
  @MockitoBean CounsellorOnboardingService onboarding;
  MockMvc mvc;
  final String token = "raw-invite-token";
  final String consultantId = "14c9b484-605f-44a3-8c36-cc11447e3a10";
  byte[] png;

  @BeforeEach
  void setup() throws Exception {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // Account-inactivity gate (UserService #1175): these callers are active.
    when(inactivity.admit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(true);
    png = PictureIntakeTest.png(2, 2);
    when(onboarding.consultantIdForOnboardingPicture(token)).thenReturn(consultantId);
  }

  MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
    return request
        .cookie(new Cookie("CSRF-TOKEN", "synthetic"))
        .header("X-CSRF-Token", "synthetic");
  }

  String picture(String prefix) {
    return prefix + "/users/account-invites/" + token + "/onboarding/picture";
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void theWizardUploadsWithoutASessionAndTheBytesAreScannedFirst(String prefix) throws Exception {
    mvc.perform(csrf(put(picture(prefix)).contentType("image/png").content(png)))
        .andExpect(status().isNoContent());
    verify(scanner).scan(png);
    verify(store).replaceForOnboarding(token, png, "image/png");
    verify(store, never()).replace(anyString(), any(), anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void theWizardSetsTheSameVisibilitySwitch(String prefix) throws Exception {
    mvc.perform(
            csrf(
                put(picture(prefix) + "/visibility")
                    .contentType("application/json")
                    .content("{\"internalOnly\":false}")))
        .andExpect(status().isNoContent());
    verify(store).writeInternalOnlyForOnboarding(token, false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void aTokenThatHasNotRegisteredYetStoresNothing(String prefix) throws Exception {
    when(onboarding.consultantIdForOnboardingPicture(token))
        .thenThrow(new BadRequestException("Registration has not happened yet for this invite"));
    mvc.perform(csrf(put(picture(prefix)).contentType("image/png").content(png)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            csrf(
                put(picture(prefix) + "/visibility")
                    .contentType("application/json")
                    .content("{\"internalOnly\":false}")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(store, scanner);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void theSameIntakeAndScannerRefusalsApplyToTheWizard(String prefix) throws Exception {
    mvc.perform(csrf(put(picture(prefix)).contentType("multipart/form-data").content(png)))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.reason").value("PICTURE_UNSUPPORTED_TYPE"));
    mvc.perform(csrf(put(picture(prefix)).contentType("image/png").content(new byte[0])))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.reason").value("PICTURE_INVALID_IMAGE"));
    doThrow(PictureException.unavailable()).when(scanner).scan(any());
    mvc.perform(csrf(put(picture(prefix)).contentType("image/png").content(png)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.reason").value("PICTURE_SCAN_UNAVAILABLE"));
    verifyNoInteractions(store);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void theWizardRouteNeverServesOrRemovesBytes(String prefix) throws Exception {
    mvc.perform(get(picture(prefix))).andExpect(status().isUnauthorized());
    mvc.perform(csrf(delete(picture(prefix)))).andExpect(status().isUnauthorized());
    verifyNoInteractions(store);
  }
}
