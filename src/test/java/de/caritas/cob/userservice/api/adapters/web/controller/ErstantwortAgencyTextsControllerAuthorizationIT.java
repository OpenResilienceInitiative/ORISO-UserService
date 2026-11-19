package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.RESTRICTED_AGENCY_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.SINGLE_TENANT_ADMIN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPlatformTextService;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A Beratungsstelle's Erstantwort texts: every admin role, narrowed by AdminScope (ADR-018 §6). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class ErstantwortAgencyTextsControllerAuthorizationIT {

  private static final String PATH = "/service/useradmin/erstantwort/agencies/5";
  private static final String BODY =
      """
      {"texts":{"greeting":{"de":"<p>Hallo</p>"}}}
      """;

  @Autowired private MockMvc mvc;
  @MockitoBean private ErstantwortPlatformTextService service;
  @MockitoBean private AdminScope adminScope;
  @MockitoBean private AgencyService agencyService;

  @BeforeEach
  void stub() {
    when(service.readAgencyTexts(5L)).thenReturn(Map.of("greeting", Map.of("de", "<p>Hallo</p>")));
    when(agencyService.getAgency(5L)).thenReturn(new AgencyDTO());
  }

  @Test
  void textsOfACentreThatDoesNotExistAreNotStored() throws Exception {
    when(agencyService.getAgency(5L)).thenReturn(null);
    var token = jwt().authorities(new SimpleGrantedAuthority(RESTRICTED_AGENCY_ADMIN));
    mvc.perform(update().with(token)).andExpect(status().isNotFound());
    verify(service, never()).replaceAgencyTexts(anyLong(), anyMap(), any());
  }

  @Test
  void anAgencyAdminOfTheCentreReadsAndSaves() throws Exception {
    var token = jwt().authorities(new SimpleGrantedAuthority(RESTRICTED_AGENCY_ADMIN));
    mvc.perform(get(PATH).with(token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.texts.greeting.de").value("<p>Hallo</p>"));
    mvc.perform(update().with(token)).andExpect(status().isOk());

    verify(service)
        .replaceAgencyTexts(eq(5L), eq(Map.of("greeting", Map.of("de", "<p>Hallo</p>"))), any());
  }

  @Test
  void aCentreOutsideTheCallersReachIsForbidden() throws Exception {
    doThrow(new ForbiddenException("outside reach")).when(adminScope).assertMay(any());
    var token = jwt().authorities(new SimpleGrantedAuthority(SINGLE_TENANT_ADMIN));

    mvc.perform(get(PATH).with(token)).andExpect(status().isForbidden());
    mvc.perform(update().with(token)).andExpect(status().isForbidden());
    verify(service, never()).replaceAgencyTexts(anyLong(), anyMap(), any());
  }

  @Test
  void counsellorsAndAnonymousCallersAreRejected() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    var counsellor = jwt().authorities(new SimpleGrantedAuthority(CONSULTANT_DEFAULT));
    mvc.perform(get(PATH).with(counsellor)).andExpect(status().isForbidden());
    mvc.perform(update().with(counsellor)).andExpect(status().isForbidden());
  }

  @Test
  void aBausteinTheCentreMayNotWriteIs400() throws Exception {
    doThrow(new IllegalArgumentException("no"))
        .when(service)
        .replaceAgencyTexts(anyLong(), anyMap(), any());
    mvc.perform(
            update().with(jwt().authorities(new SimpleGrantedAuthority(RESTRICTED_AGENCY_ADMIN))))
        .andExpect(status().isBadRequest());
  }

  private static MockHttpServletRequestBuilder update() {
    return put(PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .content(BODY)
        .cookie(new Cookie("CSRF-TOKEN", "test"))
        .header("X-CSRF-Token", "test");
  }
}
