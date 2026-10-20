package de.caritas.cob.userservice.api.service.identity.observation;

import static de.caritas.cob.userservice.api.service.identity.observation.IdentityObservation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.caritas.cob.userservice.api.adapters.keycloak.*;
import de.caritas.cob.userservice.api.adapters.web.controller.*;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringJUnitWebConfig(IdentityObservationHttpTest.Config.class)
@TestPropertySource(properties = "multitenancy.enabled=false")
class IdentityObservationHttpTest {
  @Configuration
  @EnableWebMvc
  @Import({
    SecurityConfig.class,
    RoleAuthorizationAuthorityMapper.class,
    IdentityObservationHttpAdapter.class,
    IdentityObservationService.class,
    UsernameTranscoder.class
  })
  static class Config {
    @Bean
    UserAdminController controller() throws Exception {
      var ctor = UserAdminController.class.getConstructors()[0];
      var args = Arrays.stream(ctor.getParameterTypes()).map(org.mockito.Mockito::mock).toArray();
      return (UserAdminController) ctor.newInstance(args);
    }

    @Bean
    AuthenticatedUser caller() {
      return new AuthenticatedUser(
          "human",
          "operator",
          Set.of("agency-admin", "tenant-admin", "user-admin"),
          "unused",
          0L,
          Set.of(Authority.AuthorityValue.USER_ADMIN));
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
      var w = new CsrfSecurityProperties.Whitelist();
      var h = new CsrfSecurityProperties.ConfigProperty();
      h.setProperty("X-CSRF-Whitelist");
      w.setHeader(h);
      p.setWhitelist(w);
      return p;
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired AuthenticatedUser caller;
  @MockitoBean JwtDecoder decoder;
  @MockitoBean KeycloakIdentityOccupancyLookup provider;
  @MockitoBean IdentityOccupancyRawReader reader;

  @MockitoBean
  de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService lifecycle;

  MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(lifecycle.admit(any(), any())).thenReturn(true);
    caller.setRoles(Set.of("agency-admin", "tenant-admin", "user-admin"));
    caller.setGrantedAuthorities(Set.of(Authority.AuthorityValue.USER_ADMIN));
  }

  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
      String path, String body, Set<String> roles, String... authorities) {
    return post(path)
        .contentType("application/json")
        .content(body)
        .cookie(new jakarta.servlet.http.Cookie("CSRF-TOKEN", "synthetic"))
        .header("X-CSRF-Token", "synthetic")
        .with(
            jwt()
                .jwt(j -> j.claim("realm_access", Map.of("roles", roles)).claim("tenantId", 0))
                .authorities(
                    Arrays.stream(authorities)
                        .map(
                            s ->
                                (org.springframework.security.core.GrantedAuthority)
                                    new SimpleGrantedAuthority(s))
                        .toList()));
  }

  void empty() {
    when(provider.read(any())).thenReturn(new Layer(Source.KEYCLOAK, Status.COMPLETE, List.of()));
    for (var s : List.of(Source.ADMIN, Source.CONSULTANT, Source.ASKER))
      when(reader.read(eq(s), any())).thenReturn(new Layer(s, Status.COMPLETE, List.of()));
  }

  @Test
  void generatedRouteHasOneHandlerAndCompleteEmptyReturnsNoStore() throws Exception {
    empty();
    var mappings = context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods();
    assertThat(
            mappings.entrySet().stream()
                .filter(
                    e -> e.getKey().getPatternValues().contains("/useradmin/identity-observations"))
                .count())
        .isEqualTo(1);
    mvc.perform(
            request(
                "/useradmin/identity-observations",
                "{\"username\":\"empty\"}",
                caller.getRoles(),
                Authority.AuthorityValue.USER_ADMIN))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.scope").value("identity-occupancy-v1"))
        .andExpect(jsonPath("$.identityScanComplete").value(true))
        .andExpect(jsonPath("$.occupancy").value("FREE"))
        .andExpect(jsonPath("$.layers.length()").value(4));
  }

  @Test
  void technicalPlatformShapedTokenIsDeniedBeforeAnyReads() throws Exception {
    mvc.perform(
            request(
                "/service/useradmin/identity-observations",
                "{\"username\":\"private\"}",
                Set.of("technical", "agency-admin", "tenant-admin", "user-admin"),
                Authority.AuthorityValue.USER_ADMIN,
                Authority.AuthorityValue.TECHNICAL_DEFAULT))
        .andExpect(status().isForbidden());
    verifyNoInteractions(provider, reader);
  }

  @Test
  void tenantZeroUserAdminAloneIsDeniedBeforeAnyReads() throws Exception {
    mvc.perform(
            request(
                "/useradmin/identity-observations",
                "{\"username\":\"private\"}",
                Set.of("user-admin"),
                Authority.AuthorityValue.USER_ADMIN))
        .andExpect(status().isForbidden());
    verifyNoInteractions(provider, reader);
  }

  @Test
  void wrongAuthorityDeniedBeforeAnyReads() throws Exception {
    mvc.perform(
            request(
                "/useradmin/identity-observations",
                "{\"username\":\"private\"}",
                caller.getRoles(),
                Authority.AuthorityValue.TECHNICAL_DEFAULT))
        .andExpect(status().isForbidden());
    verifyNoInteractions(provider, reader);
  }

  @Test
  void malformedSelectorsReturn400BeforeReads() throws Exception {
    mvc.perform(
            request(
                "/useradmin/identity-observations",
                "{}",
                caller.getRoles(),
                Authority.AuthorityValue.USER_ADMIN))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(provider, reader);
  }

  @Test
  void failureHasSanitized502AndNoFreeClaim() throws Exception {
    empty();
    when(provider.read(any())).thenThrow(new IllegalStateException("SECRET upstream body"));
    mvc.perform(
            request(
                "/useradmin/identity-observations",
                "{\"username\":\"empty\"}",
                caller.getRoles(),
                Authority.AuthorityValue.USER_ADMIN))
        .andExpect(status().isBadGateway())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.occupancy").value("UNKNOWN"))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
  }

  @Test
  void publicServiceContextPathUsesTheGeneratedRoute() throws Exception {
    empty();
    mvc.perform(
            request(
                    "/service/useradmin/identity-observations",
                    "{\"username\":\"empty\"}",
                    caller.getRoles(),
                    Authority.AuthorityValue.USER_ADMIN)
                .contextPath("/service"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.occupancy").value("FREE"));
  }

  @Test
  void conflictingTupleReturns409WithEverySourceFact() throws Exception {
    empty();
    when(provider.read(any()))
        .thenReturn(
            new Layer(
                Source.KEYCLOAK,
                Status.COMPLETE,
                List.of(
                    new Fact(
                        Source.KEYCLOAK,
                        "other",
                        null,
                        "alice",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false))));
    mvc.perform(
            request(
                "/useradmin/identity-observations",
                "{\"identityId\":\"requested\",\"username\":\"alice\"}",
                caller.getRoles(),
                Authority.AuthorityValue.USER_ADMIN))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.occupancy").value("CONFLICT"))
        .andExpect(jsonPath("$.facts[0].identityId").value("other"));
  }

  @Test
  void tenantBoundPlatformShapedRolesAreDeniedBeforeTargetObservations() throws Exception {
    mvc.perform(
            post("/useradmin/identity-observations")
                .contentType("application/json")
                .content("{\"username\":\"private\"}")
                .cookie(new jakarta.servlet.http.Cookie("CSRF-TOKEN", "synthetic"))
                .header("X-CSRF-Token", "synthetic")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.claim("realm_access", Map.of("roles", caller.getRoles()))
                                    .claim("tenantId", 9L))
                        .authorities(
                            new SimpleGrantedAuthority(Authority.AuthorityValue.USER_ADMIN))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(provider, reader);
  }
}
