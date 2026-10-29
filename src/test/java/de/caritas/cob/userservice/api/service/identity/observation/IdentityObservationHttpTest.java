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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringJUnitWebConfig(IdentityObservationHttpTest.Config.class)
@TestPropertySource(properties = "multitenancy.enabled=false")
class IdentityObservationHttpTest {
  @EnableWebMvc
  @Import({
    de.caritas.cob.userservice.api.adapters.web.controller.interceptor
        .ApiResponseEntityExceptionHandler.class,
    SecurityConfig.class,
    RoleAuthorizationAuthorityMapper.class,
    IdentityObservationHttpAdapter.class,
    IdentityObservationInputExceptionResolver.class,
    IdentityObservationJsonBoundaryConfiguration.class,
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
  @MockitoSpyBean IdentityObservationService observationService;
  @MockitoBean JwtDecoder decoder;
  @MockitoBean KeycloakIdentityOccupancyLookup provider;
  @MockitoBean IdentityOccupancyRawReader reader;

  @MockitoBean
  de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService accountStatus;

  @MockitoBean
  de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService lifecycle;

  MockMvc mvc;
  ch.qos.logback.classic.Level previousWebLevel;
  ch.qos.logback.classic.Level previousMethodLevel;
  final ch.qos.logback.classic.Logger methodLogger =
      (ch.qos.logback.classic.Logger)
          org.slf4j.LoggerFactory.getLogger(
              "org.springframework.web.servlet.mvc.method.annotation");
  final ch.qos.logback.classic.Logger webLogger =
      (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.web");

  @BeforeEach
  void setup() {
    previousWebLevel = webLogger.getLevel();
    previousMethodLevel = methodLogger.getLevel();
    webLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    methodLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
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
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));
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
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));
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
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));
    verifyNoInteractions(provider, reader);
  }

  @Test
  void malformedSelectorsReturn400BeforeReads() throws Exception {
    for (String body :
        List.of("{}", "{\"username\":\"PRIVATE*selector\"}", "{\"username\":\"enc.bad!\"}")) {
      mvc.perform(
              request(
                  "/useradmin/identity-observations",
                  body,
                  caller.getRoles(),
                  Authority.AuthorityValue.USER_ADMIN))
          .andExpect(status().isBadRequest())
          .andExpect(
              header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
          .andExpect(
              jsonPath("$.message")
                  .value(
                      "Provide at least one valid exact identityId, username or email; blank, overlong, control, wildcard and malformed encoded values are not allowed."))
          .andExpect(
              content()
                  .string(
                      org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE"))));
    }
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
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));
    verifyNoInteractions(provider, reader);
  }

  @Test
  void frameworkInvalidInputUsesSafeBodyWithoutLoggingSelectorValues() throws Exception {
    String marker = "PRIVATE_OBSERVATION_BOUNDARY_94d1";
    String overlong = marker + "x".repeat(256);
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var logs =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try {
      for (String body :
          List.of(
              "",
              "null",
              "{\"identityId\":\"" + overlong + "\"}",
              "{\"email\":\"" + overlong + "\"}",
              "{\"username\":\"\"}",
              "{\"username\":\"" + overlong + "\"}",
              "{\"username\":" + marker + "}",
              "{\"username\":\"" + marker,
              "{\"username\":{\"value\":\"" + marker + "\"}}")) {
        mvc.perform(
                request(
                    "/useradmin/identity-observations",
                    body,
                    caller.getRoles(),
                    Authority.AuthorityValue.USER_ADMIN))
            .andExpect(status().isBadRequest())
            .andExpect(
                header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(
                jsonPath("$.message")
                    .value(
                        "Provide at least one valid exact identityId, username or email; blank, overlong, control, wildcard and malformed encoded values are not allowed."))
            .andExpect(
                content()
                    .string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(marker))));
      }
      assertThat(
              logs.list.stream()
                  .map(
                      event ->
                          event.getFormattedMessage()
                              + (event.getThrowableProxy() == null
                                  ? ""
                                  : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                                      event.getThrowableProxy())))
                  .toList())
          .allSatisfy(
              message -> {
                assertThat(message).doesNotContain(marker, overlong);
              });
      verifyNoInteractions(provider, reader);
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
  }

  @Test
  void deniedMalformedInputDoesNotReachBindingOrTargetObservation() throws Exception {
    mvc.perform(
            request(
                "/useradmin/identity-observations",
                "{\"username\":\"PRIVATE_DENIED_94d1",
                Set.of("technical", "user-admin", "agency-admin", "tenant-admin"),
                Authority.AuthorityValue.USER_ADMIN,
                Authority.AuthorityValue.TECHNICAL_DEFAULT))
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));
    verifyNoInteractions(provider, reader);
  }

  @Test
  void frameworkLengthValidationDoesNotLogRejectedPrivateSelector() throws Exception {
    String marker = "PRIVATE_OBSERVATION_LOG_71a9";
    String rejected = marker + "x".repeat(256);
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var logs =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try {
      mvc.perform(
              request(
                  "/useradmin/identity-observations",
                  "{\"username\":\"" + rejected + "\"}",
                  caller.getRoles(),
                  Authority.AuthorityValue.USER_ADMIN))
          .andExpect(status().isBadRequest());
      assertThat(
              logs.list.stream()
                  .map(
                      event ->
                          event.getFormattedMessage()
                              + (event.getThrowableProxy() == null
                                  ? ""
                                  : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                                      event.getThrowableProxy())))
                  .toList())
          .allSatisfy(message -> assertThat(message).doesNotContain(marker, rejected));
      verifyNoInteractions(provider, reader);
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
  }

  @AfterEach
  void restoreFrameworkLogging() {
    webLogger.setLevel(previousWebLevel);
    methodLogger.setLevel(previousMethodLevel);
  }

  @Test
  void fixtureIsExcludedFromProductionComponentDiscovery() {
    var scanner =
        new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(
            true);
    var environment = new org.springframework.core.env.StandardEnvironment();
    environment.setActiveProfiles("testing");
    scanner.setEnvironment(environment);
    assertThat(
            scanner.findCandidateComponents(
                "de.caritas.cob.userservice.api.service.identity.observation"))
        .extracting(org.springframework.beans.factory.config.BeanDefinition::getBeanClassName)
        .doesNotContain(Config.class.getName());
  }

  @Test
  void successfulProbeDoesNotLogSelectorsAtDebug() throws Throwable {
    empty();
    String marker = "PrivateSuccessfulProbeR612";
    var logs =
        captureLogMessages(
            () ->
                mvc.perform(
                        request(
                            "/useradmin/identity-observations",
                            "{\"username\":\"" + marker + "\"}",
                            caller.getRoles(),
                            Authority.AuthorityValue.USER_ADMIN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.normalizedSelectors.usernames[0]").value(marker)));
    assertThat(logs).allSatisfy(message -> assertThat(message).doesNotContain(marker));
  }

  @Test
  void successfulObservationDoesNotLogNestedSourceFactsAtTrace() throws Throwable {
    empty();
    String marker = "PrivateSourceFactsC831";
    webLogger.setLevel(ch.qos.logback.classic.Level.TRACE);
    methodLogger.setLevel(ch.qos.logback.classic.Level.TRACE);
    when(provider.read(any()))
        .thenReturn(
            new Layer(
                Source.KEYCLOAK,
                Status.COMPLETE,
                List.of(
                    new Fact(
                        Source.KEYCLOAK,
                        "owner",
                        null,
                        marker,
                        marker + "@example.invalid",
                        null,
                        null,
                        null,
                        null,
                        false))));
    var logs =
        captureLogMessages(
            () ->
                mvc.perform(
                        request(
                            "/useradmin/identity-observations",
                            "{\"identityId\":\"owner\"}",
                            caller.getRoles(),
                            Authority.AuthorityValue.USER_ADMIN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.occupancy").value("OCCUPIED"))
                    .andExpect(jsonPath("$.facts[0].username").value(marker))
                    .andExpect(jsonPath("$.facts[0].email").value(marker + "@example.invalid")));
    assertThat(logs).allSatisfy(message -> assertThat(message).doesNotContain(marker));
  }

  private List<String> captureLogMessages(org.junit.jupiter.api.function.Executable action)
      throws Throwable {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var logs =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try {
      action.execute();
      return logs.list.stream()
          .map(
              event ->
                  event.getFormattedMessage()
                      + (event.getThrowableProxy() == null
                          ? ""
                          : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                              event.getThrowableProxy())))
          .toList();
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
  }

  @Test
  void canonicalUnderscoreRawAndEncodedSelectorsCanBeCompletelyFree() throws Exception {
    empty();
    String canonical = "marge_at_springfield.invalid";
    for (String name : List.of(canonical, new UsernameTranscoder().encodeUsername(canonical))) {
      mvc.perform(
              request(
                  "/useradmin/identity-observations",
                  "{\"username\":\""
                      + name
                      + "\",\"identityId\":\"owner_1439\",\"email\":\"marge_test@example.invalid\"}",
                  caller.getRoles(),
                  Authority.AuthorityValue.USER_ADMIN))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.occupancy").value("FREE"))
          .andExpect(jsonPath("$.identityScanComplete").value(true))
          .andExpect(jsonPath("$.normalizedSelectors.usernames[0]").value(canonical));
    }
  }

  @Test
  void canonicalUnderscoreSelectorsFindDisabledAndDeletedOccupancy() throws Exception {
    empty();
    String canonical = "marge_at_springfield.invalid";
    when(provider.read(any()))
        .thenReturn(
            new Layer(
                Source.KEYCLOAK,
                Status.COMPLETE,
                List.of(
                    new Fact(
                        Source.KEYCLOAK,
                        "owner_1439",
                        null,
                        canonical,
                        "marge_test@example.invalid",
                        null,
                        null,
                        null,
                        null,
                        false))));
    when(reader.read(eq(Source.ASKER), any()))
        .thenReturn(
            new Layer(
                Source.ASKER,
                Status.COMPLETE,
                List.of(
                    new Fact(
                        Source.ASKER,
                        "owner_1439",
                        "owner_1439",
                        canonical,
                        "marge_test@example.invalid",
                        9L,
                        null,
                        true,
                        "2026-01-01",
                        null))));
    for (String name : List.of(canonical, new UsernameTranscoder().encodeUsername(canonical))) {
      mvc.perform(
              request(
                  "/useradmin/identity-observations",
                  "{\"username\":\"" + name + "\"}",
                  caller.getRoles(),
                  Authority.AuthorityValue.USER_ADMIN))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.occupancy").value("OCCUPIED"))
          .andExpect(jsonPath("$.facts.length()").value(2))
          .andExpect(jsonPath("$.facts[0].enabled").value(false))
          .andExpect(jsonPath("$.facts[1].deleted").value(true));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "technical",
        "notifications-technical",
        "TECHNICAL_DEFAULT",
        "NOTIFICATIONS_TECHNICAL"
      })
  void eachTechnicalVectorIndependentlyDeniesBeforeTargetReads(String vector) throws Throwable {
    var roles = new HashSet<>(caller.getRoles());
    var authorities = new ArrayList<String>();
    authorities.add(Authority.AuthorityValue.USER_ADMIN);
    if (vector.equals("technical") || vector.equals("notifications-technical")) roles.add(vector);
    else
      authorities.add(
          vector.equals("TECHNICAL_DEFAULT")
              ? Authority.AuthorityValue.TECHNICAL_DEFAULT
              : Authority.AuthorityValue.NOTIFICATIONS_TECHNICAL);
    String marker = "PrivateIndependentDenialX811";
    var logs =
        captureLogMessages(
            () ->
                mvc.perform(
                        request(
                            "/service/useradmin/identity-observations",
                            "{\"username\":\"" + marker + "\"}",
                            roles,
                            authorities.toArray(String[]::new)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(""))
                    .andExpect(
                        header()
                            .string(
                                "Cache-Control",
                                org.hamcrest.Matchers.containsString("no-store"))));
    assertThat(logs).allSatisfy(message -> assertThat(message).doesNotContain(marker));
    verifyNoInteractions(provider, reader);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void postParsingServiceAndMappingFaultsAreSanitized500AtTrace(boolean mapping) throws Throwable {
    String marker = "PrivatePostParseFailureV177";
    webLogger.setLevel(ch.qos.logback.classic.Level.TRACE);
    methodLogger.setLevel(ch.qos.logback.classic.Level.TRACE);
    if (mapping) {
      var probe = IdentityProbe.of(null, marker, null, new UsernameTranscoder());
      doReturn(
              new IdentityObservation(
                  java.time.Instant.now(), probe, true, marker, List.of(), List.of(), 200))
          .when(observationService)
          .observe(any());
    } else {
      doThrow(new IllegalArgumentException(marker)).when(observationService).observe(any());
    }
    var logs =
        captureLogMessages(
            () ->
                mvc.perform(
                        request(
                            "/useradmin/identity-observations",
                            "{\"username\":\"" + marker + "\"}",
                            caller.getRoles(),
                            Authority.AuthorityValue.USER_ADMIN))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().string(""))
                    .andExpect(header().string("Cache-Control", "no-store")));
    assertThat(logs).allSatisfy(message -> assertThat(message).doesNotContain(marker));
    assertThat(logs)
        .anySatisfy(
            message ->
                assertThat(message)
                    .contains(
                        "Identity occupancy HTTP translation failed: java.lang.IllegalArgumentException"));
    verifyNoInteractions(provider, reader);
  }
}
