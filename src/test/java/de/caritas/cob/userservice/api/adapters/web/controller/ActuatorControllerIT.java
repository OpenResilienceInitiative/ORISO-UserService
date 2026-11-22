package de.caritas.cob.userservice.api.adapters.web.controller;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(
    properties = {
      "spring.profiles.active=testing",
      "management.endpoints.web.exposure.include=health,loggers,metrics"
    })
@AutoConfigureMockMvc(addFilters = false)
class ActuatorControllerIT {

  @Autowired private WebApplicationContext context;

  @MockitoBean private SessionTopicEnrichmentService sessionTopicEnrichmentService;

  private MockMvc mockMvc;

  @BeforeEach
  public void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void livenessAndReadinessProbesReturnStatusWithoutHealthDetails() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness").contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("UP")))
        .andExpect(jsonPath("$.components").doesNotExist());
    mockMvc
        .perform(get("/actuator/health/readiness").contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("UP")))
        .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  void loggersStayUnavailableWhenTheHttpExposureListIncludesThem() throws Exception {
    assertThat(context.getBeansOfType(LoggersEndpoint.class)).isEmpty();

    mockMvc
        .perform(get("/actuator").contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$._links.loggers").doesNotExist())
        .andExpect(jsonPath("$._links.metrics").exists());
  }

  @Test
  void endpointsThatAreNotExposedShouldReturnNotFound() throws Exception {
    mockMvc
        .perform(get("/actuator/env").contentType(APPLICATION_JSON))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(get("/actuator/beans").contentType(APPLICATION_JSON))
        .andExpect(status().isNotFound());
  }
}
