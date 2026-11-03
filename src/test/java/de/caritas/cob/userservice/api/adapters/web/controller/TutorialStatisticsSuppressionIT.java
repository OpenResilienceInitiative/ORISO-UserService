package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.TutorialProgress;
import de.caritas.cob.userservice.api.port.out.TutorialProgressRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
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
 * Small-cell suppression of the tutorial statistics with the default settings (UserService#527):
 * one user behind a group never shows as a number, five users do.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class TutorialStatisticsSuppressionIT {

  private static final String ENDPOINT = "/useradmin/statistics/tutorials";

  @Autowired private MockMvc mockMvc;
  @Autowired private TutorialProgressRepository tutorialProgressRepository;

  @MockitoBean private AuthenticatedUser authenticatedUser;

  @BeforeEach
  void seed() {
    TenantContext.clear();
    tutorialProgressRepository.deleteAll();
    save("solo", "walkthrough", 1, "completed");
    for (var i = 0; i < 5; i++) {
      save("many-" + i, "walkthrough", 2, i < 4 ? "completed" : "skipped");
    }
  }

  @AfterEach
  void cleanUp() {
    tutorialProgressRepository.deleteAll();
    TenantContext.clear();
  }

  private void save(String userId, String tourId, int tourVersion, String status) {
    var now = LocalDateTime.now();
    tutorialProgressRepository.save(
        TutorialProgress.builder()
            .userId(userId)
            .surface("frontend")
            .tourId(tourId)
            .tourVersion(tourVersion)
            .status(status)
            .startedAt(now)
            .createDate(now)
            .updateDate(now)
            .tenantId(2L)
            .build());
  }

  @Test
  void singleUserGroupIsSuppressedAndLargeGroupIsShown() throws Exception {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);

    mockMvc
        .perform(get(ENDPOINT).with(jwt().authorities(() -> AuthorityValue.TENANT_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.smallCellMinimum", Matchers.is(5)))
        .andExpect(jsonPath("$.tenants[0].counts[?(@.tourVersion==1)]", Matchers.hasSize(1)))
        .andExpect(
            jsonPath(
                "$.tenants[0].counts[?(@.tourVersion==1)].suppressed", Matchers.contains(true)))
        .andExpect(jsonPath("$.tenants[0].counts[?(@.tourVersion==1)].total", Matchers.contains(0)))
        .andExpect(
            jsonPath(
                "$.tenants[0].counts[?(@.tourVersion==2 && @.status=='completed')].total",
                Matchers.contains(4)))
        .andExpect(
            jsonPath(
                "$.tenants[0].counts[?(@.tourVersion==2 && @.status=='skipped')].total",
                Matchers.contains(1)));
  }
}
