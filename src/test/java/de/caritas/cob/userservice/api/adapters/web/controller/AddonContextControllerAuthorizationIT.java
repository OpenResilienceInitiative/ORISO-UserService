package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.NOTIFICATIONS_TECHNICAL;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TECHNICAL_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.USER_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.USER_DEFAULT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.AddonContextDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AddonContextDTO.ConsultantRef;
import de.caritas.cob.userservice.api.adapters.web.dto.AddonContextDTO.SessionRef;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.service.addon.AddonContextService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class AddonContextControllerAuthorizationIT {

  private static final String ENDPOINT = "/users/addon-context";
  private static final String ASKER_ID = "8d3c3c2e-4a8e-4b8a-9f43-6c0a0c7d1f10";
  private static final String CONSULTANT_ID = "2d18c0de-4a8e-4b8a-9f43-6c0a0c7d1f10";

  @Autowired private MockMvc mvc;

  @MockitoBean private AddonContextService addonContextService;

  @Test
  void technicalServiceTokenReadsTheContext() throws Exception {
    when(addonContextService.contextOf(ASKER_ID, 1L))
        .thenReturn(
            new AddonContextDTO(
                ASKER_ID,
                "asker",
                List.of(0L),
                List.of(new SessionRef(16L, 0L, CONSULTANT_ID, "IN_PROGRESS")),
                List.of(new ConsultantRef(CONSULTANT_ID, "florian-test-berater", "Florian Test"))));

    mvc.perform(request().with(token(TECHNICAL_DEFAULT, "technical")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(ASKER_ID))
        .andExpect(jsonPath("$.type").value("asker"))
        .andExpect(jsonPath("$.agencyIds[0]").value(0))
        .andExpect(jsonPath("$.sessions[0].id").value(16))
        .andExpect(jsonPath("$.sessions[0].agencyId").value(0))
        .andExpect(jsonPath("$.sessions[0].consultantId").value(CONSULTANT_ID))
        .andExpect(jsonPath("$.sessions[0].status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.consultants[0].id").value(CONSULTANT_ID))
        .andExpect(jsonPath("$.consultants[0].username").value("florian-test-berater"))
        .andExpect(jsonPath("$.consultants[0].displayName").value("Florian Test"));

    verify(addonContextService).contextOf(ASKER_ID, 1L);
  }

  @Test
  void unknownUserOrOtherTenantIsNotFound() throws Exception {
    when(addonContextService.contextOf(any(), anyLong()))
        .thenThrow(new NotFoundException("User not found"));

    mvc.perform(request().with(token(TECHNICAL_DEFAULT, "technical")))
        .andExpect(status().isNotFound());
  }

  @Test
  void requestWithoutTokenIsUnauthorized() throws Exception {
    mvc.perform(request()).andExpect(status().isUnauthorized());

    verifyNoInteractions(addonContextService);
  }

  @Test
  void tokensWithoutTheTechnicalRoleAreForbidden() throws Exception {
    for (var token :
        List.of(
            token(USER_DEFAULT, "user"),
            token(CONSULTANT_DEFAULT, "consultant"),
            token(USER_ADMIN, "user-admin"),
            token(NOTIFICATIONS_TECHNICAL, "notifications-technical"))) {
      mvc.perform(request().with(token)).andExpect(status().isForbidden());
    }

    verifyNoInteractions(addonContextService);
  }

  private static MockHttpServletRequestBuilder request() {
    return get(ENDPOINT).param("userId", ASKER_ID).param("tenantId", "1");
  }

  private static JwtRequestPostProcessor token(String authority, String realmRole) {
    return jwt()
        .authorities(new SimpleGrantedAuthority(authority))
        .jwt(
            token ->
                token
                    .claim("realm_access", Map.of("roles", List.of(realmRole)))
                    .claim("tenantId", 1));
  }
}
