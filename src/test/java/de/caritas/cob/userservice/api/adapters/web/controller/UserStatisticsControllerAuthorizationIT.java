package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import de.caritas.cob.userservice.api.service.statistics.SessionStatisticsService;
import de.caritas.cob.userservice.api.statistics.model.SessionStatisticsResultDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(
    properties = {
      "spring.profiles.active=testing",
      "spring.datasource.url=jdbc:h2:mem:userstatistics-auth;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
      "spring.datasource.username=sa",
      "spring.datasource.password=sa",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
      "keycloak.auth-server-url=https://auth.testing",
      "keycloak.realm=testing",
      "keycloak.config.admin-username=admin",
      "keycloak.config.admin-password=secret",
      "identity.openid-connect-url=https://auth.testing/realms/testing/protocol/openid-connect",
      "rocket.technical.username=technical",
      "rocket.technical.password=secret",
      "rocket-chat.base-url=https://testing.com/api/v1",
      "rocket-chat.mongo-url=mongodb://localhost:27017/testing",
      "consulting.type.service.api.url=https://consulting-type.testing/service",
      "tenant.service.api.url=https://tenant.testing/service",
      "matrix.apiUrl=https://matrix.testing",
      "matrix.registrationSharedSecret=secret"
    })
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = Replace.ANY)
@ActiveProfiles("testing")
class UserStatisticsControllerAuthorizationIT {

  @Autowired private MockMvc mvc;

  @MockitoBean private SessionStatisticsService sessionStatisticsService;

  @MockitoBean SessionTopicEnrichmentService sessionTopicEnrichmentService;

  @Test
  void getSessionStatistics_Should_ReturnUnauthorized_WhenNoKeycloakAuthorization()
      throws Exception {
    mvc.perform(get("/userstatistics/sessions").param("sessionId", "1"))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(sessionStatisticsService);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_DEFAULT})
  void getSessionStatistics_Should_ReturnForbidden_WhenNoTechnicalAuthority() throws Exception {
    mvc.perform(get("/userstatistics/sessions").param("sessionId", "1"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(sessionStatisticsService);
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.TECHNICAL_DEFAULT})
  void getSessionStatistics_Should_ReturnOk_WhenTechnicalUserIsAuthorized() throws Exception {
    when(sessionStatisticsService.retrieveSession(1L, null))
        .thenReturn(new SessionStatisticsResultDTO());

    mvc.perform(get("/userstatistics/sessions").param("sessionId", "1")).andExpect(status().isOk());
  }
}
