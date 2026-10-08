package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.CaseHandoverLogsService;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(CaseHandoverConsentPreferenceHttpTest.Config.class)
@TestPropertySource(properties = "multitenancy.enabled=false")
class CaseHandoverConsentPreferenceHttpTest {
  @Configuration
  @EnableWebMvc
  @Import({
    SecurityConfig.class,
    RoleAuthorizationAuthorityMapper.class,
    CaseHandoverController.class,
    ApiResponseEntityExceptionHandler.class
  })
  static class Config {
    @Bean
    CsrfSecurityProperties csrfSecurityProperties() {
      var properties = new CsrfSecurityProperties();
      var cookie = new CsrfSecurityProperties.ConfigProperty();
      cookie.setProperty("CSRF-TOKEN");
      properties.setCookie(cookie);
      var header = new CsrfSecurityProperties.ConfigProperty();
      header.setProperty("X-CSRF-Token");
      properties.setHeader(header);
      var whitelist = new CsrfSecurityProperties.Whitelist();
      var whitelistHeader = new CsrfSecurityProperties.ConfigProperty();
      whitelistHeader.setProperty("X-CSRF-Whitelist");
      whitelist.setHeader(whitelistHeader);
      properties.setWhitelist(whitelist);
      return properties;
    }
  }

  @Autowired WebApplicationContext context;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean AuthenticatedUser caller;

  @MockitoBean
  de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService inactivity;

  @MockitoBean CaseHandoverService service;
  @MockitoBean CaseHandoverLogsService logs;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(inactivity.admit(any(), any())).thenReturn(true);
  }

  String route(String prefix) {
    return prefix + "/users/sessions/123/case-handover/consent-preference";
  }

  MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
    return request
        .cookie(new Cookie("CSRF-TOKEN", "test-token"))
        .header("X-CSRF-Token", "test-token");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void registeredAskerMayReadAndSaveButPayloadMustContainTheChoice(String prefix) throws Exception {
    when(service.getConsentPreference(123L))
        .thenReturn(new CaseHandoverService.ConsentPreference(123L, false));
    when(service.updateConsentPreference(123L, true))
        .thenReturn(new CaseHandoverService.ConsentPreference(123L, true));
    var asker = user("asker").authorities(new SimpleGrantedAuthority(USER_DEFAULT));
    mvc.perform(get(route(prefix)).with(asker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alwaysAskBeforeAdditionalAccess").value(false));
    mvc.perform(
            csrf(put(route(prefix))
                    .contentType("application/json")
                    .content("{\"alwaysAskBeforeAdditionalAccess\":true}"))
                .with(asker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alwaysAskBeforeAdditionalAccess").value(true));
    mvc.perform(csrf(put(route(prefix)).contentType("application/json").content("{}")).with(asker))
        .andExpect(status().isBadRequest());
    verify(service).updateConsentPreference(123L, true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/service"})
  void staffAnonymousAndUnauthenticatedCallersCannotReadOrSave(String prefix) throws Exception {
    for (var role : java.util.List.of(CONSULTANT_DEFAULT, USER_ADMIN, ANONYMOUS_DEFAULT)) {
      var staff = user("other").authorities(new SimpleGrantedAuthority(role));
      mvc.perform(get(route(prefix)).with(staff)).andExpect(status().isForbidden());
      mvc.perform(
              csrf(put(route(prefix))
                      .contentType("application/json")
                      .content("{\"alwaysAskBeforeAdditionalAccess\":true}"))
                  .with(staff))
          .andExpect(status().isForbidden());
    }
    mvc.perform(get(route(prefix))).andExpect(status().isUnauthorized());
    mvc.perform(
            csrf(
                put(route(prefix))
                    .contentType("application/json")
                    .content("{\"alwaysAskBeforeAdditionalAccess\":true}")))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }
}
