package de.caritas.cob.userservice.api.adapters.web.controller;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureMockMvc(addFilters = false)
class ActuatorControllerIT {

  @Autowired private WebApplicationContext context;

  @MockBean private SessionTopicEnrichmentService sessionTopicEnrichmentService;

  private MockMvc mockMvc;

  @BeforeEach
  public void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void getHealtcheck_Should_returnHealtcheck() throws Exception {

    // when // then
    mockMvc
        .perform(get("/actuator/health").contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("status", is("UP")));
  }

  @Test
  void updateLoggerLevel_Should_ChangeLogLevel() throws Exception {
    // given
    String loggerName = "de.caritas.cob.userservice.api.adapters.web.controller";
    String newLevel = "DEBUG";

    // when
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/actuator/loggers/" + loggerName)
                .contentType(APPLICATION_JSON)
                .content("{\"configuredLevel\": \"" + newLevel + "\"}"))
        .andExpect(status().isNoContent());

    // then
    mockMvc
        .perform(get("/actuator/loggers/" + loggerName).contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configuredLevel", is(newLevel)));
  }

  @Test
  void getHealtcheck_Should_return403ByCsrfRulesForEndpointsNotExposed() throws Exception {
    mockMvc
        .perform(get("/actuator/env").contentType(APPLICATION_JSON))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(get("/actuator/beans").contentType(APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }
}
