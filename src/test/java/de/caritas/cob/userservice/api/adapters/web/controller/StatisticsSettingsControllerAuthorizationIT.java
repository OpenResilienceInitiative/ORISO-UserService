package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.SINGLE_TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TECHNICAL_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.USER_ADMIN;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService.SettingChangeEntry;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService.SettingState;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService.StatisticsSettings;
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

/** The global statistics settings are for platform admins only (OBP #93). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class StatisticsSettingsControllerAuthorizationIT {

  private static final String SETTINGS = "/service/useradmin/statistics/settings";
  private static final String HISTORY = "/service/useradmin/statistics/settings/history";
  private static final String BODY =
      """
      {"values":{"agencyMinimumCounsellors":"2"},"revision":4}
      """;
  private static final StatisticsSettings SETTINGS_RESPONSE =
      new StatisticsSettings(
          List.of(
              new SettingState(
                  "agencyMinimumCounsellors",
                  "2",
                  "5",
                  List.of("2", "3"),
                  "2026-11-01T09:30",
                  "platform.admin")),
          5L);

  @Autowired private MockMvc mvc;

  @MockitoBean private StatisticsSettingsService statisticsSettingsService;

  @BeforeEach
  void stubService() {
    when(statisticsSettingsService.getSettings()).thenReturn(SETTINGS_RESPONSE);
    when(statisticsSettingsService.updateSettings(anyMap(), anyLong()))
        .thenReturn(SETTINGS_RESPONSE);
    when(statisticsSettingsService.getHistory())
        .thenReturn(
            List.of(
                new SettingChangeEntry(
                    "agencyMinimumCounsellors",
                    "5",
                    "2",
                    "id",
                    "platform.admin",
                    "2026-11-01T09:30")));
  }

  @Test
  void platformAdminCanReadTheSettings() throws Exception {
    mvc.perform(get(SETTINGS).with(adminToken(0, USER_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings[0].key").value("agencyMinimumCounsellors"))
        .andExpect(jsonPath("$.settings[0].effectiveFrom").value("2026-11-01T09:30"))
        .andExpect(jsonPath("$.revision").value(5));
  }

  @Test
  void platformAdminCanSaveTheSettings() throws Exception {
    mvc.perform(update().with(adminToken(0, USER_ADMIN))).andExpect(status().isOk());

    verify(statisticsSettingsService).updateSettings(Map.of("agencyMinimumCounsellors", "2"), 4L);
  }

  @Test
  void platformAdminCanReadTheProtocol() throws Exception {
    mvc.perform(get(HISTORY).with(adminToken(0, USER_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].oldValue").value("5"))
        .andExpect(jsonPath("$[0].newValue").value("2"))
        .andExpect(jsonPath("$[0].changedByName").value("platform.admin"));
  }

  @Test
  void unauthenticatedCallsAreRejected() throws Exception {
    mvc.perform(get(SETTINGS)).andExpect(status().isUnauthorized());
    mvc.perform(update()).andExpect(status().isUnauthorized());
    mvc.perform(get(HISTORY)).andExpect(status().isUnauthorized());

    verifyNoInteractions(statisticsSettingsService);
  }

  @Test
  void everyoneButThePlatformAdminIsForbidden() throws Exception {
    for (var token :
        List.of(
            // a Träger admin carries the same two roles, but not the platform tenant 0
            adminToken(1, USER_ADMIN),
            adminToken(1, TENANT_ADMIN),
            jwt().authorities(new SimpleGrantedAuthority(USER_ADMIN)),
            jwt().authorities(new SimpleGrantedAuthority(SINGLE_TENANT_ADMIN)),
            jwt().authorities(new SimpleGrantedAuthority(TECHNICAL_DEFAULT)),
            jwt().authorities(new SimpleGrantedAuthority(CONSULTANT_DEFAULT)))) {
      mvc.perform(get(SETTINGS).with(token)).andExpect(status().isForbidden());
      mvc.perform(update().with(token)).andExpect(status().isForbidden());
      mvc.perform(get(HISTORY).with(token)).andExpect(status().isForbidden());
    }

    verifyNoInteractions(statisticsSettingsService);
  }

  private static MockHttpServletRequestBuilder update() {
    return put(SETTINGS)
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
