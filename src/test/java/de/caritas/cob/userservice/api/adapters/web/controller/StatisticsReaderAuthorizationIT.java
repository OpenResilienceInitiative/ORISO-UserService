package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.RESTRICTED_AGENCY_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.SINGLE_TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.STATISTICS_READER;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TECHNICAL_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.USER_ADMIN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.dto.AdminResponseDTO;
import de.caritas.cob.userservice.api.admin.service.admin.StatisticsReaderUserService;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStatisticsService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsReaderSecondFactorGate;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService;
import de.caritas.cob.userservice.api.service.tutorial.TutorialStatisticsService;
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

/**
 * ORISO-UserService#1666: the read-only statistics role. Only a platform admin manages these
 * accounts; the reader itself reaches the platform dashboard and its own account basics, and
 * nothing else — enforced here, not by hiding pages in the Admin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class StatisticsReaderAuthorizationIT {

  private static final String READERS = "/service/useradmin/statisticsreaders";
  private static final String READER = READERS + "/0b4c7b8e-4a71-4b8c-9a71-1c2f3d4e5f60";
  private static final String DASHBOARD = "/service/useradmin/statistics/dashboard";
  private static final String TUTORIALS = "/service/useradmin/statistics/tutorials";
  private static final String CREATE_BODY =
      """
      {"username":"stats.reader","firstname":"Stat","lastname":"Reader","email":"stats@example.org"}
      """;
  private static final String UPDATE_BODY =
      """
      {"firstname":"Stat","lastname":"Reader","email":"stats@example.org"}
      """;

  @Autowired private MockMvc mvc;

  @MockitoBean private StatisticsReaderUserService statisticsReaderUserService;
  @MockitoBean private AdminDashboardStatisticsService adminDashboardStatisticsService;
  @MockitoBean private TutorialStatisticsService tutorialStatisticsService;
  @MockitoBean private StatisticsSettingsService statisticsSettingsService;
  @MockitoBean private StatisticsReaderSecondFactorGate secondFactorGate;
  @MockitoBean private UserAccountControllerDelegate userAccountControllerDelegate;

  @BeforeEach
  void stubServices() {
    when(statisticsReaderUserService.create(any())).thenReturn(new AdminResponseDTO());
    when(statisticsReaderUserService.find(anyString())).thenReturn(new AdminResponseDTO());
    when(statisticsReaderUserService.update(anyString(), any())).thenReturn(new AdminResponseDTO());
    when(statisticsReaderUserService.search(anyString(), any()))
        .thenReturn(
            Map.of(
                "admins", List.of(), "totalElements", 0, "isFirstPage", true, "isLastPage", true));
  }

  @Test
  void platformAdminManagesStatisticsReaders() throws Exception {
    var platformAdmin = platformAdmin();

    mvc.perform(withCsrf(post(READERS)).content(CREATE_BODY).with(platformAdmin))
        .andExpect(status().isCreated());
    mvc.perform(get(READERS + "/search").with(platformAdmin)).andExpect(status().isOk());
    mvc.perform(get(READER).with(platformAdmin)).andExpect(status().isOk());
    mvc.perform(withCsrf(put(READER)).content(UPDATE_BODY).with(platformAdmin))
        .andExpect(status().isOk());
    mvc.perform(withCsrf(delete(READER)).with(platformAdmin)).andExpect(status().isOk());

    verify(statisticsReaderUserService).create(any());
    verify(statisticsReaderUserService).delete(anyString());
  }

  @Test
  void nobodyButThePlatformAdminManagesStatisticsReaders() throws Exception {
    for (var token :
        List.of(
            // a Träger admin carries the same two roles, but not the platform tenant 0
            adminToken(1, USER_ADMIN, "agency-admin", "tenant-admin"),
            adminToken(1, TENANT_ADMIN, "agency-admin", "tenant-admin"),
            adminToken(0, USER_ADMIN, "restricted-agency-admin", "user-admin"),
            adminToken(0, RESTRICTED_AGENCY_ADMIN, "restricted-agency-admin"),
            adminToken(0, SINGLE_TENANT_ADMIN, "single-tenant-admin"),
            statisticsReader(0),
            jwt().authorities(new SimpleGrantedAuthority(USER_ADMIN)),
            jwt().authorities(new SimpleGrantedAuthority(TECHNICAL_DEFAULT)),
            jwt().authorities(new SimpleGrantedAuthority(CONSULTANT_DEFAULT)))) {
      mvc.perform(withCsrf(post(READERS)).content(CREATE_BODY).with(token))
          .andExpect(status().isForbidden());
      mvc.perform(get(READERS + "/search").with(token)).andExpect(status().isForbidden());
      mvc.perform(get(READER).with(token)).andExpect(status().isForbidden());
      mvc.perform(withCsrf(put(READER)).content(UPDATE_BODY).with(token))
          .andExpect(status().isForbidden());
      mvc.perform(withCsrf(delete(READER)).with(token)).andExpect(status().isForbidden());
    }

    verifyNoInteractions(statisticsReaderUserService);
  }

  @Test
  void statisticsReaderReadsThePlatformDashboard() throws Exception {
    mvc.perform(get(DASHBOARD).with(statisticsReader(0))).andExpect(status().isOk());
    mvc.perform(get(TUTORIALS).with(statisticsReader(0))).andExpect(status().isOk());
  }

  @Test
  void statisticsReaderLoadsItsOwnAccountData() throws Exception {
    mvc.perform(get("/users/data").with(statisticsReader(0))).andExpect(status().isOk());

    verify(userAccountControllerDelegate).getUserData();
  }

  @Test
  void statisticsReaderOutsideThePlatformTenantIsRefused() throws Exception {
    mvc.perform(get(DASHBOARD).with(statisticsReader(3))).andExpect(status().isForbidden());
    mvc.perform(get(TUTORIALS).with(statisticsReader(3))).andExpect(status().isForbidden());
    mvc.perform(get(DASHBOARD).with(statisticsReaderWithoutTenant()))
        .andExpect(status().isForbidden());

    verifyNoInteractions(adminDashboardStatisticsService, tutorialStatisticsService);
  }

  @Test
  void statisticsReaderIsRefusedEverywhereElse() throws Exception {
    var reader = statisticsReader(0);
    for (var request :
        List.of(
            get("/service/useradmin/statistics/settings"),
            get("/service/useradmin/statistics/settings/history"),
            get("/service/useradmin/tenantadmins/search").param("query", "*"),
            get("/service/useradmin/agencyadmins/search").param("query", "*"),
            get("/service/useradmin/supportadmins/search"),
            get("/useradmin/consultants"),
            get("/useradmin/data"),
            get("/service/useradmin/account-invites"),
            get("/users/consultants/search").param("query", "*"),
            get("/users/statistics/registration"),
            get("/service/users/statistics/consultant"),
            get("/users/sessions/askers"),
            get("/service/users/supervisors/logs"),
            get("/userstatistics/test"),
            get("/users/handshakes/pending"),
            get("/users/support-rooms"))) {
      mvc.perform(request.with(reader)).andExpect(status().isForbidden());
    }
    mvc.perform(withCsrf(post("/useradmin/consultants")).content("{}").with(reader))
        .andExpect(status().isForbidden());
    mvc.perform(withCsrf(post("/service/useradmin/tenantadmins")).content("{}").with(reader))
        .andExpect(status().isForbidden());

    verifyNoInteractions(statisticsSettingsService, statisticsReaderUserService);
  }

  @Test
  void searchRejectsUnknownSortFields() throws Exception {
    mvc.perform(get(READERS + "/search").param("field", "PASSWORD").with(platformAdmin()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(READERS + "/search")
                .param("field", "LASTNAME")
                .param("order", "DESC")
                .with(platformAdmin()))
        .andExpect(status().isOk());
  }

  @Test
  void searchRejectsOversizedPagesAndMalformedQueries() throws Exception {
    mvc.perform(get(READERS + "/search").param("perPage", "1000").with(platformAdmin()))
        .andExpect(status().isBadRequest());
    mvc.perform(get(READERS + "/search").param("query", "%zz").with(platformAdmin()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unauthenticatedCallsAreRejected() throws Exception {
    mvc.perform(get(READERS + "/search")).andExpect(status().isUnauthorized());
    mvc.perform(get(DASHBOARD)).andExpect(status().isUnauthorized());

    verifyNoInteractions(statisticsReaderUserService, adminDashboardStatisticsService);
  }

  private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .cookie(new Cookie("CSRF-TOKEN", "test"))
        .header("X-CSRF-Token", "test");
  }

  /** Same shape as the Admin's super-admin token: both admin roles plus tenant id 0. */
  private static JwtRequestPostProcessor platformAdmin() {
    return adminToken(0, USER_ADMIN, "agency-admin", "tenant-admin", "user-admin");
  }

  private static JwtRequestPostProcessor statisticsReader(int tenantId) {
    return adminToken(tenantId, STATISTICS_READER, "statistics-reader");
  }

  private static JwtRequestPostProcessor statisticsReaderWithoutTenant() {
    return jwt()
        .authorities(new SimpleGrantedAuthority(STATISTICS_READER))
        .jwt(token -> token.claim("realm_access", Map.of("roles", List.of("statistics-reader"))));
  }

  private static JwtRequestPostProcessor adminToken(
      int tenantId, String authority, String... realmRoles) {
    return jwt()
        .authorities(new SimpleGrantedAuthority(authority))
        .jwt(
            token ->
                token
                    .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                    .claim("tenantId", tenantId));
  }
}
