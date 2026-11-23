package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.RESTRICTED_AGENCY_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.SINGLE_TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TENANT_ADMIN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.service.journey.JourneyConflictException;
import de.caritas.cob.userservice.api.service.journey.JourneyService;
import de.caritas.cob.userservice.api.service.journey.JourneyService.Stored;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

/**
 * Journeys (ADR-036 part E, #1689): every admin reads platform journeys, only the platform admin
 * writes them; layers are narrowed by AdminScope; stale versions are 409.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class JourneysControllerAuthorizationIT {

  private static final String BASE = "/service/useradmin/journeys";
  private static final String JOURNEY_BODY =
      """
      {"version":0,"journey":{"id":"j1","conversationType":"AGENCY_COUNSELLING"}}
      """;
  private static final String LAYER_BODY =
      """
      {"version":0,"phases":{"flight":[]}}
      """;

  @Autowired private MockMvc mvc;
  @MockitoBean private JourneyService service;
  @MockitoBean private AdminScope adminScope;

  @BeforeEach
  void stub() {
    when(service.platformJourneys()).thenReturn(List.of(new Stored("j1", 3, Map.of("id", "j1"))));
    when(service.savePlatformJourney(anyString(), anyLong(), anyMap(), any()))
        .thenReturn(new Stored("j1", 1, Map.of("id", "j1")));
    when(service.saveLayer(anyString(), anyLong(), anyString(), anyLong(), anyMap(), any()))
        .thenReturn(
            Optional.of(new Stored("j1", 1, Map.of("phases", Map.of("flight", List.of())))));
    when(adminScope.current()).thenReturn(new AdminScope.Platform());
  }

  @Test
  void everyAdminReadsThePlatformJourneys() throws Exception {
    for (var token :
        List.of(
            adminToken(0, TENANT_ADMIN),
            adminToken(1, TENANT_ADMIN),
            jwt().authorities(new SimpleGrantedAuthority(RESTRICTED_AGENCY_ADMIN)))) {
      mvc.perform(get(BASE + "/platform").with(token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].journeyId").value("j1"))
          .andExpect(jsonPath("$[0].version").value(3));
    }
  }

  @Test
  void onlyThePlatformAdminWritesPlatformJourneys() throws Exception {
    mvc.perform(write(BASE + "/platform/j1", JOURNEY_BODY).with(adminToken(0, TENANT_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));

    for (var token :
        List.of(
            adminToken(1, TENANT_ADMIN),
            jwt().authorities(new SimpleGrantedAuthority(SINGLE_TENANT_ADMIN)),
            jwt().authorities(new SimpleGrantedAuthority(RESTRICTED_AGENCY_ADMIN)),
            jwt().authorities(new SimpleGrantedAuthority(CONSULTANT_DEFAULT)))) {
      mvc.perform(write(BASE + "/platform/j1", JOURNEY_BODY).with(token))
          .andExpect(status().isForbidden());
    }
    verify(service).savePlatformJourney(eq("j1"), eq(0L), anyMap(), any());
  }

  @Test
  void aStaleVersionIs409WithTheCurrentOne() throws Exception {
    when(service.savePlatformJourney(anyString(), anyLong(), anyMap(), any()))
        .thenThrow(new JourneyConflictException(4));

    mvc.perform(write(BASE + "/platform/j1", JOURNEY_BODY).with(adminToken(0, TENANT_ADMIN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("JOURNEY_CHANGED"))
        .andExpect(jsonPath("$.currentVersion").value(4));
  }

  @Test
  void aJourneyAgainstTheRulesIs400() throws Exception {
    when(service.savePlatformJourney(anyString(), anyLong(), anyMap(), any()))
        .thenThrow(new IllegalArgumentException("the postcode comes before the centre"));

    mvc.perform(write(BASE + "/platform/j1", JOURNEY_BODY).with(adminToken(0, TENANT_ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("the postcode comes before the centre"));
  }

  @Test
  void aTraegerWritesItsOwnLayer() throws Exception {
    mvc.perform(write(BASE + "/tenants/5/layers/j1", LAYER_BODY).with(adminToken(5, TENANT_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phases.flight").isArray());
    verify(service).saveLayer(eq("TENANT"), eq(5L), eq("j1"), eq(0L), anyMap(), any());
  }

  @Test
  void aLayerOutsideTheCallersReachIsForbidden() throws Exception {
    doThrow(new ForbiddenException("outside reach")).when(adminScope).assertMay(any());

    mvc.perform(write(BASE + "/tenants/6/layers/j1", LAYER_BODY).with(adminToken(5, TENANT_ADMIN)))
        .andExpect(status().isForbidden());
    mvc.perform(
            write(BASE + "/agencies/9/layers/j1", LAYER_BODY)
                .with(jwt().authorities(new SimpleGrantedAuthority(RESTRICTED_AGENCY_ADMIN))))
        .andExpect(status().isForbidden());
    verify(service, never())
        .saveLayer(anyString(), anyLong(), anyString(), anyLong(), anyMap(), any());
  }

  @Test
  void aCentreAdminReadsTheLayerOfItsOwnTraeger() throws Exception {
    when(adminScope.current()).thenReturn(new AdminScope.Agencies(5L, Set.of(9L)));
    doThrow(new ForbiddenException("not a Träger admin")).when(adminScope).assertMay(any());
    when(service.layers("TENANT", 5L)).thenReturn(List.of());

    mvc.perform(
            get(BASE + "/tenants/5/layers")
                .with(jwt().authorities(new SimpleGrantedAuthority(RESTRICTED_AGENCY_ADMIN))))
        .andExpect(status().isOk());
    mvc.perform(
            get(BASE + "/tenants/6/layers")
                .with(jwt().authorities(new SimpleGrantedAuthority(RESTRICTED_AGENCY_ADMIN))))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedCallsAreRejected() throws Exception {
    mvc.perform(get(BASE + "/platform")).andExpect(status().isUnauthorized());
    mvc.perform(write(BASE + "/platform/j1", JOURNEY_BODY)).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  private static MockHttpServletRequestBuilder write(String path, String body) {
    return put(path)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
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
