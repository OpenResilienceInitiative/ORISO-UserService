package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.RESTRICTED_AGENCY_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.SINGLE_TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.USER_ADMIN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPlatformTextService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The platform greeting texts are written by the platform admin only (ADR-018 §6). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class ErstantwortPlatformTextsControllerAuthorizationIT {

  private static final String PATH = "/service/useradmin/erstantwort/platform";
  private static final String BODY =
      """
      {"texts":{"greeting":{"de":"<p>Hallo</p>","en":""}}}
      """;

  @Autowired private MockMvc mvc;
  @MockitoBean private ErstantwortPlatformTextService service;

  @BeforeEach
  void stub() {
    when(service.readPlatformTexts())
        .thenReturn(Map.of("greeting", Map.of("de", "<p>Plattform</p>")));
  }

  @Test
  void platformAdminReadsStoredTextsAndTheBuiltInDefaults() throws Exception {
    mvc.perform(get(PATH).with(adminToken(0, TENANT_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.texts.greeting.de").value("<p>Plattform</p>"))
        .andExpect(jsonPath("$.builtIn.greeting.en").isNotEmpty())
        .andExpect(jsonPath("$.builtIn.greeting.['de@informal']").isNotEmpty())
        .andExpect(jsonPath("$.builtIn.emergencyNumbers.ti").isNotEmpty())
        .andExpect(jsonPath("$.builtIn.whoReadsAlong").doesNotExist());
  }

  @Test
  void platformAdminSavesTexts() throws Exception {
    mvc.perform(update().with(adminToken(0, TENANT_ADMIN))).andExpect(status().isOk());

    verify(service)
        .replacePlatformTexts(Map.of("greeting", Map.of("de", "<p>Hallo</p>", "en", "")), "user");
  }

  @Test
  void anInvalidBausteinOrLanguageIs400() throws Exception {
    doThrow(new IllegalArgumentException("no"))
        .when(service)
        .replacePlatformTexts(anyMap(), anyString());

    mvc.perform(update().with(adminToken(0, TENANT_ADMIN))).andExpect(status().isBadRequest());
  }

  @Test
  void unauthenticatedCallsAreRejected() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    mvc.perform(update()).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test
  void everyoneButThePlatformAdminIsForbidden() throws Exception {
    for (var token :
        List.of(
            // a Träger admin may carry the same two roles, but not the platform tenant 0
            adminToken(1, TENANT_ADMIN),
            adminToken(1, USER_ADMIN),
            jwt().authorities(new SimpleGrantedAuthority(SINGLE_TENANT_ADMIN)),
            jwt().authorities(new SimpleGrantedAuthority(RESTRICTED_AGENCY_ADMIN)),
            jwt().authorities(new SimpleGrantedAuthority(CONSULTANT_DEFAULT)))) {
      mvc.perform(get(PATH).with(token)).andExpect(status().isForbidden());
      mvc.perform(update().with(token)).andExpect(status().isForbidden());
    }
    verify(service, org.mockito.Mockito.never()).replacePlatformTexts(any(), any());
  }

  private static MockHttpServletRequestBuilder update() {
    return put(PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .content(BODY)
        .cookie(new Cookie("CSRF-TOKEN", "test"))
        .header("X-CSRF-Token", "test");
  }

  /** Same shape as the Admin's super-admin token: both admin roles plus a tenant id claim. */
  private static JwtRequestPostProcessor adminToken(int tenantId, String authority) {
    return jwt()
        .authorities(new SimpleGrantedAuthority(authority))
        .jwt(
            token ->
                token
                    .claim("realm_access", Map.of("roles", List.of("agency-admin", "tenant-admin")))
                    .claim("tenantId", tenantId));
  }
}
