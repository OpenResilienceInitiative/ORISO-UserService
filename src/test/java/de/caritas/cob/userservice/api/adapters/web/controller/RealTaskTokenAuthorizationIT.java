package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.HttpTenantFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.EmailNotificationsDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.facade.userdata.AskerDataProvider;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantImportService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.*;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Native issued tokens, actual socket/decoder/security/tenant filters and production controller.
 */
@SpringBootTest(
    classes = RealTaskTokenAuthorizationIT.ReceivingApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"testing", "native-task-fixture"})
@EnabledIfEnvironmentVariable(named = "ORISO_TASK_TOKEN_FIXTURE", matches = ".+")
@TestPropertySource(
    properties = {
      "multitenancy.enabled=true",
      "feature.multitenancy.with.single.domain.enabled=true",
      "feature.topics.enabled=false",
      "csrf.header.property=csrfHeader",
      "csrf.cookie.property=csrfCookie",
      "task.identity.audience=userservice"
    })
class RealTaskTokenAuthorizationIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  @LocalServerPort int port;
  @Autowired UserAccountService accounts;
  @Autowired AskerDataProvider askerData;
  @Autowired ConsultantImportService importer;
  @Autowired AccountInactivityService lifecycle;

  private static JsonNode fixture() {
    try {
      return JSON.readTree(Files.readString(Path.of(System.getenv("ORISO_TASK_TOKEN_FIXTURE"))));
    } catch (Exception failure) {
      throw new IllegalStateException("Private native test fixture unavailable");
    }
  }

  @DynamicPropertySource
  static void nativeBindings(DynamicPropertyRegistry properties) {
    var fixture = fixture();
    String issuer = fixture.path("issuer").asText();
    properties.add("identity.openid-connect-url", () -> issuer + "/protocol/openid-connect");
    properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
    properties.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> issuer + "/protocol/openid-connect/certs");
    for (var task : fixture.path("tasks")) {
      String key = task.path("key").asText();
      if (Arrays.stream(TaskIdentity.values())
          .anyMatch(candidate -> candidate.name().equals(key))) {
        String prefix = "IDENTITY_" + key;
        properties.add(prefix + "_CLIENT_ID", () -> task.path("clientId").asText());
        properties.add(prefix + "_SERVICE_SUBJECT", () -> task.path("subject").asText());
        properties.add("KEYCLOAK_" + key + "_CLIENT_SECRET", () -> task.path("secret").asText());
      }
      if (key.equals("CONSULTANT_IMPORT")) {
        properties.add(
            "identity.consultant-import.client-id", () -> task.path("clientId").asText());
        properties.add(
            "identity.consultant-import.service-subject", () -> task.path("subject").asText());
      }
    }
  }

  private String token(String key) throws Exception {
    JsonNode task = null;
    for (var candidate : fixture().path("tasks"))
      if (candidate.path("key").asText().equals(key)) task = candidate;
    if (task == null) throw new IllegalArgumentException("Unknown native fixture task");
    String form =
        "grant_type=client_credentials&client_id="
            + URLEncoder.encode(task.path("clientId").asText(), StandardCharsets.UTF_8)
            + "&client_secret="
            + URLEncoder.encode(task.path("secret").asText(), StandardCharsets.UTF_8);
    var response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        fixture().path("issuer").asText() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body()).path("access_token").asText();
  }

  private HttpResponse<String> call(String method, String path, String token) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .header("csrfHeader", "test")
            .header("Cookie", "csrfCookie=test");
    if (token != null) request.header("Authorization", "Bearer " + token);
    return HTTP.send(
        request.method(method, HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @BeforeEach
  void domainFixture() {
    reset(accounts, askerData, importer, lifecycle);
    when(lifecycle.snapshot(anyString())).thenReturn(Optional.empty());
    var user =
        new User("synthetic-preference-target", 7L, "synthetic", "fixture@example.invalid", false);
    when(accounts.findConsultantByEmail("fixture@example.invalid")).thenReturn(Optional.empty());
    when(accounts.findUserByEmail("fixture@example.invalid")).thenReturn(Optional.of(user));
    when(askerData.retrieveData(user))
        .thenReturn(
            de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO.builder()
                .emailNotifications(new EmailNotificationsDTO().emailNotificationsEnabled(true))
                .build());
  }

  @Test
  void nativeNotificationTaskWithoutTenantClaimReadsActualControllerPreferencesOnly()
      throws Exception {
    String actor = token("NOTIFICATION_DISPATCH");
    var response = call("GET", "/users/notifications?email=fixture%40example.invalid", actor);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).path("emailNotificationsEnabled").asBoolean())
        .isTrue();
    verify(accounts).findUserByEmail("fixture@example.invalid");
    assertThat(call("GET", "/useradmin/consultants", actor).statusCode()).isEqualTo(403);
    assertThat(call("POST", "/users/consultants/import", actor).statusCode()).isEqualTo(403);
    verifyNoInteractions(importer);
  }

  @Test
  void nativeImporterWithoutTenantClaimReachesExistingImportControllerOnly() throws Exception {
    doAnswer(
            invocation -> {
              assertThat(TenantContext.getCurrentTenant()).isEqualTo(0L);
              return null;
            })
        .when(importer)
        .startImport();
    String actor = token("CONSULTANT_IMPORT");
    assertThat(call("POST", "/users/consultants/import", actor).statusCode()).isEqualTo(200);
    verify(importer).startImport();
    assertThat(
            call("GET", "/users/notifications?email=fixture%40example.invalid", actor).statusCode())
        .isEqualTo(403);
    assertThat(call("GET", "/useradmin/consultants", actor).statusCode()).isEqualTo(403);
    verifyNoInteractions(accounts);
  }

  @Test
  void nativeSignedForeignBindingsAndMixedHumanRolesNeverReachDomainServices() throws Exception {
    for (String key : List.of("NOTIFICATION_DISPATCH", "CONSULTANT_IMPORT"))
      for (String variant : List.of("mixedRoles", "wrongAudience", "wrongSubject")) {
        String actor = fixture().path("variants").path(key).path(variant).asText();
        assertThat(actor).isNotBlank();
        String method = key.equals("CONSULTANT_IMPORT") ? "POST" : "GET";
        String path =
            key.equals("CONSULTANT_IMPORT")
                ? "/users/consultants/import"
                : "/users/notifications?email=fixture%40example.invalid";
        assertThat(call(method, path, actor).statusCode()).isEqualTo(403);
      }
    verifyNoInteractions(accounts, importer);
  }

  @Test
  void tamperedNativeSignatureIsRejectedBeforeTenantOrDomainWork() throws Exception {
    String actor = token("NOTIFICATION_DISPATCH");
    int offset = actor.lastIndexOf('.') + 1;
    String changed =
        actor.substring(0, offset)
            + (actor.charAt(offset) == 'A' ? 'B' : 'A')
            + actor.substring(offset + 1);
    assertThat(
            call("GET", "/users/notifications?email=fixture%40example.invalid", changed)
                .statusCode())
        .isEqualTo(401);
    verifyNoInteractions(accounts, importer);
  }

  /** Domain-service stubs are explicit; authorization and tenancy use their production beans. */
  @Configuration(proxyBeanMethods = false)
  @Profile("native-task-fixture")
  @EnableAutoConfiguration(
      excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
        "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration"
      })
  @Import({
    SecurityConfig.class,
    IdentityConfig.class,
    CsrfSecurityProperties.class,
    RoleAuthorizationAuthorityMapper.class,
    TechnicalOrSuperAdminUserTenantResolver.class,
    AccessTokenTenantResolver.class
  })
  static class ReceivingApp {
    private final Map<Class<?>, Object> dependencies = new HashMap<>();

    @Bean
    UserAccountService accounts() {
      return (UserAccountService) dependency(UserAccountService.class);
    }

    @Bean
    AskerDataProvider askerData() {
      return (AskerDataProvider) dependency(AskerDataProvider.class);
    }

    @Bean
    ConsultantImportService importer() {
      return (ConsultantImportService) dependency(ConsultantImportService.class);
    }

    @Bean
    AccountInactivityService lifecycle() {
      return (AccountInactivityService) dependency(AccountInactivityService.class);
    }

    @Bean
    TenantService tenant() {
      return (TenantService) dependency(TenantService.class);
    }

    @Bean
    TenantResolverService tenancy(
        TechnicalOrSuperAdminUserTenantResolver tasks, AccessTokenTenantResolver claims) {
      var service =
          new TenantResolverService(
              mock(CustomHeaderTenantResolver.class),
              mock(SubdomainTenantResolver.class),
              tasks,
              claims,
              mock(MultitenancyWithSingleDomainTenantResolver.class));
      ReflectionTestUtils.setField(service, "multitenancyWithSingleDomain", true);
      return service;
    }

    @Bean
    HttpTenantFilter tenantFilter(TenantResolverService resolver, TenantService tenant) {
      return new HttpTenantFilter(resolver, tenant);
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<HttpTenantFilter>
        noDuplicateTenantFilter(HttpTenantFilter filter) {
      var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
      registration.setEnabled(false);
      return registration;
    }

    @Bean
    UserController controller() {
      return (UserController) construct(UserController.class);
    }

    private Object dependency(Class<?> type) {
      var existing = dependencies.get(type);
      if (existing != null) return existing;
      var value =
          type == UserAccountControllerDelegate.class || type == UserSupportControllerDelegate.class
              ? construct(type)
              : mock(type);
      dependencies.put(type, value);
      return value;
    }

    private Object construct(Class<?> type) {
      try {
        var constructor =
            Arrays.stream(type.getDeclaredConstructors())
                .max(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount))
                .orElseThrow();
        constructor.setAccessible(true);
        return constructor.newInstance(
            Arrays.stream(constructor.getParameterTypes()).map(this::dependency).toArray());
      } catch (ReflectiveOperationException failure) {
        throw new IllegalStateException(
            "Production receiving controller fixture cannot be wired", failure);
      }
    }
  }
}
