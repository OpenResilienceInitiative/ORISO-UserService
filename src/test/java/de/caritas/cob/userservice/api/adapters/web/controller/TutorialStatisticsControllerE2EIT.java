package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.TutorialProgress;
import de.caritas.cob.userservice.api.port.out.TutorialProgressRepository;
import java.time.LocalDateTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authorization matrix and count correctness for the aggregate tutorial statistics endpoint (epic
 * TOUR-06): tenant admins see only their tenant, platform admins see global counts, everyone else
 * is rejected, and no response ever contains an individual user's tutorial history.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class TutorialStatisticsControllerE2EIT {

  private static final String ENDPOINT = "/useradmin/statistics/tutorials";

  @Autowired private MockMvc mockMvc;
  @Autowired private TutorialProgressRepository tutorialProgressRepository;

  @MockitoBean private AuthenticatedUser authenticatedUser;

  @BeforeEach
  void seedProgressAcrossTenants() {
    tutorialProgressRepository.deleteAll();
    save("user-a", "frontend", "consultant-walkthrough", 1, "completed", 2L);
    save("user-b", "frontend", "consultant-walkthrough", 1, "completed", 2L);
    save("user-c", "frontend", "consultant-walkthrough", 1, "in_progress", 2L);
    save("user-d", "admin", "admin-demo-tour", 1, "skipped", 3L);
  }

  @AfterEach
  void cleanUp() {
    tutorialProgressRepository.deleteAll();
  }

  private void save(
      String userId, String surface, String tourId, int tourVersion, String status, Long tenantId) {
    var now = LocalDateTime.now();
    tutorialProgressRepository.save(
        TutorialProgress.builder()
            .userId(userId)
            .surface(surface)
            .tourId(tourId)
            .tourVersion(tourVersion)
            .status(status)
            .startedAt(now)
            .createDate(now)
            .updateDate(now)
            .tenantId(tenantId)
            .build());
  }

  @Test
  void tutorialStatistics_requiresAuthentication() throws Exception {
    mockMvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
  }

  @Test
  void tutorialStatistics_rejectsConsultants() throws Exception {
    mockMvc
        .perform(get(ENDPOINT).with(jwt().authorities(() -> AuthorityValue.CONSULTANT_DEFAULT)))
        .andExpect(status().isForbidden());
  }

  @Test
  void tutorialStatistics_rejectsAdviceSeekers() throws Exception {
    mockMvc
        .perform(get(ENDPOINT).with(jwt().authorities(() -> AuthorityValue.USER_DEFAULT)))
        .andExpect(status().isForbidden());
  }

  @Test
  void tutorialStatistics_rejectsRestrictedAgencyAdmins() throws Exception {
    mockMvc
        .perform(
            get(ENDPOINT).with(jwt().authorities(() -> AuthorityValue.RESTRICTED_AGENCY_ADMIN)))
        .andExpect(status().isForbidden());
  }

  @Test
  void tutorialStatistics_tenantAdminSeesOnlyOwnTenantWithCorrectCounts() throws Exception {
    when(authenticatedUser.isTenantSuperAdmin()).thenReturn(true);
    when(authenticatedUser.getTenantId()).thenReturn(2L);

    mockMvc
        .perform(get(ENDPOINT).with(jwt().authorities(() -> AuthorityValue.TENANT_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope", Matchers.is("TENANT")))
        .andExpect(jsonPath("$.tenants", Matchers.hasSize(1)))
        .andExpect(jsonPath("$.tenants[0].tenantId", Matchers.is(2)))
        .andExpect(jsonPath("$.tenants[0].counts", Matchers.hasSize(2)))
        .andExpect(
            jsonPath("$.tenants[0].counts[?(@.status=='completed')].total", Matchers.contains(2)))
        .andExpect(
            jsonPath(
                "$.tenants[0].counts[?(@.status=='in_progress')].total", Matchers.contains(1)));
  }

  @Test
  void tutorialStatistics_platformAdminSeesGlobalCountsGroupedByTenant() throws Exception {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);

    mockMvc
        .perform(get(ENDPOINT).with(jwt().authorities(() -> AuthorityValue.TENANT_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope", Matchers.is("PLATFORM")))
        .andExpect(jsonPath("$.tenants", Matchers.hasSize(2)))
        .andExpect(jsonPath("$.tenants[0].tenantId", Matchers.is(2)))
        .andExpect(jsonPath("$.tenants[1].tenantId", Matchers.is(3)))
        .andExpect(jsonPath("$.tenants[1].counts[0].tourId", Matchers.is("admin-demo-tour")))
        .andExpect(jsonPath("$.tenants[1].counts[0].total", Matchers.is(1)));
  }

  @Test
  void tutorialStatistics_neverExposesIndividualUserRecords() throws Exception {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);

    var body =
        mockMvc
            .perform(get(ENDPOINT).with(jwt().authorities(() -> AuthorityValue.TENANT_ADMIN)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("userId", "user-a", "user-b", "user-c", "user-d");
  }
}
