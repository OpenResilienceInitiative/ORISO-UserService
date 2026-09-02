package de.caritas.cob.userservice.api.service.consultingtype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.config.CacheManagerConfig;
import de.caritas.cob.userservice.api.config.apiclient.ApplicationSettingsApiControllerFactory;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.httpheader.HttpHeadersResolver;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.applicationsettingsservice.generated.ApiClient;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.ApplicationsettingsControllerApi;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsDTO;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

class ApplicationSettingsServiceTest {

  private static final String CSRF_HEADER = "X-CSRF-TOKEN";
  private static final String CSRF_VALUE = "csrf-token";
  private static final String AUTH_HEADER = "Authorization";
  private static final String AUTH_VALUE = "Bearer keycloak-token";
  private static final String TENANT_HEADER = "tenantId";
  private static final String TENANT_VALUE = "7";

  private StubApplicationsettingsControllerApi controllerApi;
  private RecordingApiClient apiClient;
  private SecurityHeaderSupplier securityHeaderSupplier;
  private TenantHeaderSupplier tenantHeaderSupplier;
  private ApplicationSettingsService applicationSettingsService;

  @BeforeEach
  void setUp() {
    apiClient = new RecordingApiClient();
    controllerApi = new StubApplicationsettingsControllerApi(apiClient);
    securityHeaderSupplier = createSecurityHeaderSupplier();
    tenantHeaderSupplier = createTenantHeaderSupplier();
    applicationSettingsService =
        new ApplicationSettingsService(
            new StubApplicationSettingsApiControllerFactory(controllerApi),
            securityHeaderSupplier,
            tenantHeaderSupplier);
  }

  // Feature toggles and multitenancy config are loaded from application settings service.
  @Test
  void getApplicationSettings_happyPath_returnsDtoFromApi() {
    var expected = new ApplicationSettingsDTO();
    controllerApi.settingsResult = expected;

    ApplicationSettingsDTO result = applicationSettingsService.getApplicationSettings();

    assertThat(result).isSameAs(expected);
    assertThat(controllerApi.settingsCallCount.get()).isEqualTo(1);
  }

  // Outbound calls must include CSRF protection expected by consulting-type service.
  @Test
  void getApplicationSettings_happyPath_appliesCsrfHeadersToApiClient() {
    controllerApi.settingsResult = new ApplicationSettingsDTO();

    applicationSettingsService.getApplicationSettings();

    assertThat(apiClient.recordedHeaders).containsKey(CSRF_HEADER);
    assertThat(apiClient.recordedHeaders.get(CSRF_HEADER)).isNotBlank();
  }

  // Tenant context must be forwarded for multitenancy-aware settings retrieval.
  @Test
  void getApplicationSettings_happyPath_appliesTenantHeader() {
    controllerApi.settingsResult = new ApplicationSettingsDTO();

    applicationSettingsService.getApplicationSettings();

    assertThat(apiClient.recordedHeaders).containsEntry(TENANT_HEADER, TENANT_VALUE);
  }

  // Settings fetch has no local fallback; callers must handle upstream outages.
  @Test
  void getApplicationSettings_apiFailure_propagatesRestClientException() {
    controllerApi.settingsException = new RestClientException("settings down");

    assertThatThrownBy(() -> applicationSettingsService.getApplicationSettings())
        .isInstanceOf(RestClientException.class)
        .hasMessage("settings down");
  }

  // Super-admin SMTP test flow needs fully populated credentials from settings service.
  @Test
  void getGlobalSmtpCredentials_validCredentials_returnsOptionalOfCredentials() {
    var credentials =
        new ApplicationSettingsSmtpCredentialsDTO()
            .globalSmtpUsername("smtp-user")
            .globalSmtpPassword("smtp-pass");
    controllerApi.smtpResult = credentials;

    Optional<ApplicationSettingsSmtpCredentialsDTO> result =
        applicationSettingsService.getGlobalSmtpCredentials();

    assertThat(result).contains(credentials);
  }

  // Missing remote payload must degrade to empty rather than NPE downstream.
  @Test
  void getGlobalSmtpCredentials_nullCredentials_returnsEmpty() {
    controllerApi.smtpResult = null;

    assertThat(applicationSettingsService.getGlobalSmtpCredentials()).isEmpty();
  }

  // Partial SMTP config is treated as unusable for test-email sending.
  @Test
  void getGlobalSmtpCredentials_blankUsername_returnsEmpty() {
    controllerApi.smtpResult =
        new ApplicationSettingsSmtpCredentialsDTO()
            .globalSmtpUsername("  ")
            .globalSmtpPassword("secret");

    assertThat(applicationSettingsService.getGlobalSmtpCredentials()).isEmpty();
  }

  @Test
  void getGlobalSmtpCredentials_blankPassword_returnsEmpty() {
    controllerApi.smtpResult =
        new ApplicationSettingsSmtpCredentialsDTO()
            .globalSmtpUsername("user")
            .globalSmtpPassword("");

    assertThat(applicationSettingsService.getGlobalSmtpCredentials()).isEmpty();
  }

  @Test
  void getGlobalSmtpCredentials_bothFieldsBlank_returnsEmpty() {
    controllerApi.smtpResult =
        new ApplicationSettingsSmtpCredentialsDTO()
            .globalSmtpUsername("")
            .globalSmtpPassword("   ");

    assertThat(applicationSettingsService.getGlobalSmtpCredentials()).isEmpty();
  }

  // SMTP credentials endpoint is super-admin protected and needs Keycloak auth headers.
  @Test
  void getGlobalSmtpCredentials_happyPath_usesKeycloakAndCsrfHeaders() {
    controllerApi.smtpResult =
        new ApplicationSettingsSmtpCredentialsDTO()
            .globalSmtpUsername("user")
            .globalSmtpPassword("pass");
    var keycloakSupplier = new TrackingSecurityHeaderSupplier(createAuthenticatedUser());
    apiClient.recordedHeaders.clear();
    applicationSettingsService =
        new ApplicationSettingsService(
            new StubApplicationSettingsApiControllerFactory(controllerApi),
            keycloakSupplier,
            tenantHeaderSupplier);

    applicationSettingsService.getGlobalSmtpCredentials();

    assertThat(keycloakSupplier.keycloakHeaderCalls.get()).isEqualTo(1);
    assertThat(apiClient.recordedHeaders).containsEntry(AUTH_HEADER, AUTH_VALUE);
  }

  // SMTP credential lookup is best-effort and must not break admin tooling on 4xx/5xx.
  @Test
  void getGlobalSmtpCredentials_restClientException_returnsEmpty() {
    controllerApi.smtpException = new RestClientException("settings unreachable");

    assertThat(applicationSettingsService.getGlobalSmtpCredentials()).isEmpty();
  }

  @Test
  void getGlobalSmtpCredentials_httpClientErrorException_returnsEmpty() {
    controllerApi.smtpException =
        HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null);

    assertThat(applicationSettingsService.getGlobalSmtpCredentials()).isEmpty();
  }

  // #1006: a swallowed RestClientException made "invite mail not sent" undiagnosable. The
  // degradation stays (best-effort), but status and cause must reach the log at WARN.
  @Test
  void getGlobalSmtpCredentials_httpClientErrorException_logsWarnWithStatusAndContext() {
    withCapturedLogs(
        appender -> {
          controllerApi.smtpException =
              HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null);

          assertThat(applicationSettingsService.getGlobalSmtpCredentials()).isEmpty();

          assertThat(appender.list)
              .anySatisfy(
                  event -> {
                    assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(event.getFormattedMessage())
                        .contains("SMTP credentials")
                        .contains("403");
                    // Review 3893332413: the exception itself (root cause + stack trace) must
                    // reach the log, not just its message.
                    assertThat(event.getThrowableProxy()).isNotNull();
                    assertThat(event.getThrowableProxy().getClassName()).contains("Forbidden");
                  });
        });
  }

  @Test
  void getGlobalSmtpCredentials_restClientExceptionWithoutResponse_logsWarnWithExceptionType() {
    withCapturedLogs(
        appender -> {
          controllerApi.smtpException = new RestClientException("settings unreachable");

          assertThat(applicationSettingsService.getGlobalSmtpCredentials()).isEmpty();

          assertThat(appender.list)
              .anySatisfy(
                  event -> {
                    assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(event.getFormattedMessage())
                        .contains("SMTP credentials")
                        .contains("RestClientException");
                    assertThat(event.getThrowableProxy()).isNotNull();
                    assertThat(event.getThrowableProxy().getClassName())
                        .contains("RestClientException");
                  });
        });
  }

  // #1006: blank credentials from the endpoint are a configuration state worth a WARN — but the
  // credential values themselves must never be logged.
  @Test
  void getGlobalSmtpCredentials_blankPassword_logsWarnWithoutCredentialValues() {
    withCapturedLogs(
        appender -> {
          controllerApi.smtpResult =
              new ApplicationSettingsSmtpCredentialsDTO()
                  .globalSmtpUsername("smtp-account-name")
                  .globalSmtpPassword("");

          assertThat(applicationSettingsService.getGlobalSmtpCredentials()).isEmpty();

          assertThat(appender.list)
              .anySatisfy(
                  event -> {
                    assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("SMTP credentials");
                  });
          assertThat(appender.list)
              .noneSatisfy(
                  event -> assertThat(event.getFormattedMessage()).contains("smtp-account-name"));
        });
  }

  private void withCapturedLogs(
      java.util.function.Consumer<
              ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>>
          test) {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(ApplicationSettingsService.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      test.accept(appender);
    } finally {
      logger.detachAppender(appender);
    }
  }

  // Sensitive SMTP credentials must always be fetched fresh, never from cache.
  @Test
  void getGlobalSmtpCredentials_calledTwice_callsApiTwice() {
    controllerApi.smtpResult =
        new ApplicationSettingsSmtpCredentialsDTO()
            .globalSmtpUsername("user")
            .globalSmtpPassword("pass");

    applicationSettingsService.getGlobalSmtpCredentials();
    applicationSettingsService.getGlobalSmtpCredentials();

    assertThat(controllerApi.smtpCallCount.get()).isEqualTo(2);
  }

  @Nested
  @SpringJUnitConfig(classes = ApplicationSettingsServiceTest.CacheTestConfig.class)
  @TestPropertySource(
      properties = {
        "multitenancy.enabled=true",
        "csrf.header.property=X-CSRF-TOKEN",
        "csrf.cookie.property=CSRF-TOKEN"
      })
  class CachingBehavior {

    @Autowired private ApplicationSettingsService cachedApplicationSettingsService;

    @Autowired private StubApplicationsettingsControllerApi controllerApi;

    @Autowired private RecordingApiClient apiClient;

    @Autowired private TrackingSecurityHeaderSupplier securityHeaderSupplier;

    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
      cacheManager.getCache(CacheManagerConfig.APPLICATION_SETTINGS_CACHE).clear();
      controllerApi.reset();
      apiClient.recordedHeaders.clear();
      securityHeaderSupplier.reset();
    }

    // Application settings are stable and safe to cache for the lifetime of the process.
    @Test
    void getApplicationSettings_calledTwice_callsApiOnce() {
      controllerApi.settingsResult = new ApplicationSettingsDTO();

      cachedApplicationSettingsService.getApplicationSettings();
      cachedApplicationSettingsService.getApplicationSettings();

      assertThat(controllerApi.settingsCallCount.get()).isEqualTo(1);
    }

    // Header wiring runs on the cache-miss path that populates the shared settings entry.
    @Test
    void getApplicationSettings_calledTwice_wiresHeadersOnlyOnFirstCall() {
      controllerApi.settingsResult = new ApplicationSettingsDTO();

      cachedApplicationSettingsService.getApplicationSettings();
      cachedApplicationSettingsService.getApplicationSettings();

      assertThat(securityHeaderSupplier.csrfOnlyHeaderCalls.get()).isEqualTo(1);
      assertThat(apiClient.recordedHeaders).containsKey(CSRF_HEADER);
    }
  }

  private static SecurityHeaderSupplier createSecurityHeaderSupplier() {
    SecurityHeaderSupplier supplier = new SecurityHeaderSupplier(createAuthenticatedUser());
    ReflectionTestUtils.setField(supplier, "csrfHeaderProperty", CSRF_HEADER);
    ReflectionTestUtils.setField(supplier, "csrfCookieProperty", "CSRF-TOKEN");
    return supplier;
  }

  private static AuthenticatedUser createAuthenticatedUser() {
    AuthenticatedUser user = new AuthenticatedUser();
    user.setUserId("user-id");
    user.setUsername("username");
    user.setAccessToken(AUTH_VALUE.replace("Bearer ", ""));
    user.setRoles(Set.of());
    user.setGrantedAuthorities(Set.of());
    return user;
  }

  private static TenantHeaderSupplier createTenantHeaderSupplier() {
    TenantHeaderSupplier supplier =
        new TenantHeaderSupplier(new HttpHeadersResolver()) {
          @Override
          public void addTenantHeader(HttpHeaders headers) {
            headers.add(TENANT_HEADER, TENANT_VALUE);
          }
        };
    ReflectionTestUtils.setField(supplier, "multitenancy", true);
    return supplier;
  }

  @Configuration
  @EnableCaching
  static class CacheTestConfig {

    @Bean
    RecordingApiClient recordingApiClient() {
      return new RecordingApiClient();
    }

    @Bean
    StubApplicationsettingsControllerApi stubApplicationsettingsControllerApi(
        RecordingApiClient recordingApiClient) {
      return new StubApplicationsettingsControllerApi(recordingApiClient);
    }

    @Bean
    ApplicationSettingsService applicationSettingsService(
        StubApplicationsettingsControllerApi stubApplicationsettingsControllerApi,
        TrackingSecurityHeaderSupplier trackingSecurityHeaderSupplier,
        TenantHeaderSupplier tenantHeaderSupplier) {
      return new ApplicationSettingsService(
          new StubApplicationSettingsApiControllerFactory(stubApplicationsettingsControllerApi),
          trackingSecurityHeaderSupplier,
          tenantHeaderSupplier);
    }

    @Bean
    TrackingSecurityHeaderSupplier trackingSecurityHeaderSupplier() {
      return new TrackingSecurityHeaderSupplier(createAuthenticatedUser());
    }

    @Bean
    TenantHeaderSupplier tenantHeaderSupplier() {
      return new TenantHeaderSupplier(new HttpHeadersResolver());
    }

    @Bean
    CacheManager cacheManager() {
      var cacheManager = new SimpleCacheManager();
      cacheManager.setCaches(
          List.of(new ConcurrentMapCache(CacheManagerConfig.APPLICATION_SETTINGS_CACHE)));
      cacheManager.initializeCaches();
      return cacheManager;
    }
  }

  static final class RecordingApiClient extends ApiClient {

    final Map<String, String> recordedHeaders = new HashMap<>();

    @Override
    public ApiClient addDefaultHeader(String name, String value) {
      if (recordedHeaders != null) {
        recordedHeaders.put(name, value);
      }
      return super.addDefaultHeader(name, value);
    }
  }

  static final class StubApplicationsettingsControllerApi extends ApplicationsettingsControllerApi {

    ApplicationSettingsDTO settingsResult;
    ApplicationSettingsSmtpCredentialsDTO smtpResult;
    RuntimeException settingsException;
    RuntimeException smtpException;
    final AtomicInteger settingsCallCount = new AtomicInteger();
    final AtomicInteger smtpCallCount = new AtomicInteger();

    StubApplicationsettingsControllerApi(ApiClient apiClient) {
      super(apiClient);
    }

    void reset() {
      settingsResult = null;
      smtpResult = null;
      settingsException = null;
      smtpException = null;
      settingsCallCount.set(0);
      smtpCallCount.set(0);
    }

    @Override
    public ApplicationSettingsDTO getApplicationSettings() {
      settingsCallCount.incrementAndGet();
      if (settingsException != null) {
        throw settingsException;
      }
      return settingsResult != null ? settingsResult : new ApplicationSettingsDTO();
    }

    @Override
    public ApplicationSettingsSmtpCredentialsDTO getGlobalSmtpCredentials() {
      smtpCallCount.incrementAndGet();
      if (smtpException != null) {
        throw smtpException;
      }
      return smtpResult;
    }
  }

  static final class StubApplicationSettingsApiControllerFactory
      extends ApplicationSettingsApiControllerFactory {

    private final ApplicationsettingsControllerApi api;

    StubApplicationSettingsApiControllerFactory(ApplicationsettingsControllerApi api) {
      this.api = api;
    }

    @Override
    public ApplicationsettingsControllerApi createControllerApi() {
      return api;
    }
  }

  static final class TrackingSecurityHeaderSupplier extends SecurityHeaderSupplier {

    final AtomicInteger csrfOnlyHeaderCalls = new AtomicInteger();
    final AtomicInteger keycloakHeaderCalls = new AtomicInteger();

    TrackingSecurityHeaderSupplier(AuthenticatedUser authenticatedUser) {
      super(authenticatedUser);
      ReflectionTestUtils.setField(this, "csrfHeaderProperty", CSRF_HEADER);
      ReflectionTestUtils.setField(this, "csrfCookieProperty", "CSRF-TOKEN");
    }

    void reset() {
      csrfOnlyHeaderCalls.set(0);
      keycloakHeaderCalls.set(0);
    }

    @Override
    public HttpHeaders getCsrfHttpHeaders() {
      csrfOnlyHeaderCalls.incrementAndGet();
      HttpHeaders headers = super.getCsrfHttpHeaders();
      headers.add(TENANT_HEADER, TENANT_VALUE);
      return headers;
    }

    @Override
    public HttpHeaders getKeycloakAndCsrfHttpHeaders() {
      keycloakHeaderCalls.incrementAndGet();
      HttpHeaders headers = super.getKeycloakAndCsrfHttpHeaders();
      headers.add(TENANT_HEADER, TENANT_VALUE);
      return headers;
    }
  }
}
