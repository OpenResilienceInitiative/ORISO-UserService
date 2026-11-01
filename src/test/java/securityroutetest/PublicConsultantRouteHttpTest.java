package securityroutetest;

// Outside de.caritas.cob.userservice on purpose (like cachetest): AppConfig component-scans that
// whole package, so the nested stub controller and config would otherwise be registered in every
// other test's application context and break them with an ambiguous mapping.

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import de.caritas.cob.userservice.api.config.auth.RoleAuthorizationAuthorityMapper;
import de.caritas.cob.userservice.api.config.auth.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * ORISO-UserService#1534: the real filter chain for the anonymous consultant lookup behind a public
 * contact link. A stub controller answers 200, so only the security matcher is under test.
 */
@SpringJUnitWebConfig(PublicConsultantRouteHttpTest.Config.class)
@TestPropertySource(properties = "multitenancy.enabled=false")
class PublicConsultantRouteHttpTest {

  @RestController
  static class StubConsultantController {
    @GetMapping("/users/consultants/{consultantId}")
    String consultant(@PathVariable String consultantId) {
      return consultantId;
    }
  }

  @Configuration
  @EnableWebMvc
  @Import({SecurityConfig.class, RoleAuthorizationAuthorityMapper.class})
  static class Config {
    @Bean
    StubConsultantController stubConsultantController() {
      return new StubConsultantController();
    }

    @Bean
    CsrfSecurityProperties csrfSecurityProperties() {
      var p = new CsrfSecurityProperties();
      var cookie = new CsrfSecurityProperties.ConfigProperty();
      cookie.setProperty("CSRF-TOKEN");
      p.setCookie(cookie);
      var header = new CsrfSecurityProperties.ConfigProperty();
      header.setProperty("X-CSRF-Token");
      p.setHeader(header);
      var whitelist = new CsrfSecurityProperties.Whitelist();
      var wh = new CsrfSecurityProperties.ConfigProperty();
      wh.setProperty("X-CSRF-Whitelist");
      whitelist.setHeader(wh);
      p.setWhitelist(whitelist);
      return p;
    }
  }

  @Autowired WebApplicationContext context;
  @MockitoBean JwtDecoder jwtDecoder;

  @MockitoBean
  de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService inactivity;

  @MockitoBean
  de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService
      accountOperationalStatus;

  MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(inactivity.admit(any(), any())).thenReturn(true);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "nikunj-rohit",
        "qa-shazia-2810",
        "team-2",
        "beratung-24",
        "65c1095e-b977-493a-a34f-064b729d1d6c"
      })
  void anonymousVisitorsCanOpenAPublicContactLink(String consultantId) throws Exception {
    mvc.perform(get("/users/consultants/" + consultantId)).andExpect(status().isOk());
  }

  @ParameterizedTest
  @ValueSource(strings = {"search", "absences", "import", "sessions", "qa--2810", "-qa", "qa-"})
  void siblingRoutesAndMalformedNamesStayProtected(String consultantId) throws Exception {
    mvc.perform(get("/users/consultants/" + consultantId)).andExpect(status().isUnauthorized());
  }
}
