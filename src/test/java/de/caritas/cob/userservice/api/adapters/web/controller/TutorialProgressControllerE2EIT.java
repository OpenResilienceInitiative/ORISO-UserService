package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.TutorialProgressRepository;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real-slice regression for the versioned tutorial-progress API (TOUR-03): JWT-authenticated
 * MockMvc against the running Spring context and the JPA persistence layer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class TutorialProgressControllerE2EIT {

  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TutorialProgressRepository tutorialProgressRepository;

  @MockitoBean private AuthenticatedUser authenticatedUser;

  @AfterEach
  void cleanUp() {
    tutorialProgressRepository.deleteAll();
  }

  @Test
  void tutorialProgress_requiresAuthentication() throws Exception {
    mockMvc
        .perform(get("/users/tutorials/progress").param("surface", "frontend"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tutorialProgress_roundTripsOwnVersionedProgress() throws Exception {
    org.mockito.Mockito.when(authenticatedUser.getUserId()).thenReturn("tutorial-user-1");
    var payload =
        Map.of(
            "surface", "frontend",
            "tourId", "consultant-walkthrough",
            "tourVersion", 1,
            "status", "completed",
            "currentStepId", "profile");

    mockMvc
        .perform(
            put("/users/tutorials/progress")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .with(jwt().authorities(() -> AuthorityValue.CONSULTANT_DEFAULT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("completed")));

    mockMvc
        .perform(
            get("/users/tutorials/progress")
                .param("surface", "frontend")
                .with(jwt().authorities(() -> AuthorityValue.CONSULTANT_DEFAULT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].tourId", is("consultant-walkthrough")))
        .andExpect(jsonPath("$[0].tourVersion", is(1)))
        .andExpect(jsonPath("$[0].status", is("completed")));
  }

  @Test
  void tutorialProgress_rejectsUnknownStatusWithBadRequest() throws Exception {
    org.mockito.Mockito.when(authenticatedUser.getUserId()).thenReturn("tutorial-user-1");
    var payload =
        Map.of(
            "surface", "frontend",
            "tourId", "consultant-walkthrough",
            "tourVersion", 1,
            "status", "finished");

    mockMvc
        .perform(
            put("/users/tutorials/progress")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .with(jwt().authorities(() -> AuthorityValue.CONSULTANT_DEFAULT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isBadRequest());
  }
}
