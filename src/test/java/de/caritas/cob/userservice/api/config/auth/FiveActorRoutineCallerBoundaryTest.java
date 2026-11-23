package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.TenantCaseHandoverPolicyReadClient;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantCreationClient;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.notification.AgencyContactDetailsClient;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTenantDTO;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/** Caller wire proof with a local synthetic RSA issuer; native receiver acceptance is separate. */
class FiveActorRoutineCallerBoundaryTest {
  SyntheticTaskIssuer issuer;
  IdentityClientConfig identity;
  SecurityHeaderSupplier headers;
  TenantAdminServiceApiControllerFactory tenantFactory;
  NoLegacyAuthentication legacy;
  AtomicInteger downstreamCalls;
  AtomicReference<String> downstreamClient;
  AtomicReference<String> requestBody;
  AtomicReference<String> requestTarget;
  AuthenticatedUser caller;

  @BeforeEach
  void setup() throws Exception {
    issuer = new SyntheticTaskIssuer();
    var legacyConfig = new IdentityConfig();
    legacyConfig.setOpenidConnectUrl(issuer.base());
    var technical = new TechnicalUserConfig();
    technical.setClientId("legacy-technical");
    technical.setClientSecret("synthetic-legacy-secret");
    legacyConfig.setTechnicalUser(technical);
    var authentication =
        new KeycloakAuthClient(new RestTemplate(), new AuthenticatedUser(), legacyConfig);
    ReflectionTestUtils.setField(authentication, "keycloakClientId", "app");
    var keycloak = new KeycloakConfig();
    keycloak.setAuthServerUrl(issuer.base());
    keycloak.setRealm("synthetic");
    var decoder = NimbusJwtDecoder.withJwkSetUri(issuer.base() + "/certs").build();
    var broker =
        new TaskActorTokenBroker(
            FiveActorConfigurationTest.configured(FiveActorConfigurationTest.environment()),
            authentication,
            decoder,
            keycloak);
    var beans = new StaticListableBeanFactory();
    beans.addBean("taskBroker", broker);
    identity =
        new TaskAwareIdentityClientConfig(
            legacyConfig,
            FiveActorConfigurationTest.environment(),
            beans.getBeanProvider(TaskActorTokenBroker.class));
    caller = new AuthenticatedUser();
    headers = new SecurityHeaderSupplier(caller);
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "X-CSRF-TOKEN");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "CSRF-TOKEN");
    tenantFactory = new TenantAdminServiceApiControllerFactory();
    ReflectionTestUtils.setField(tenantFactory, "tenantServiceApiUrl", issuer.base());
    ReflectionTestUtils.setField(tenantFactory, "restTemplate", new RestTemplate());
    legacy = new NoLegacyAuthentication();
    downstreamCalls = new AtomicInteger();
    downstreamClient = new AtomicReference<>();
    requestBody = new AtomicReference<>();
    requestTarget = new AtomicReference<>();
    issuer.server.createContext(
        "/tenantadmin",
        exchange -> {
          downstreamCalls.incrementAndGet();
          String authorization = exchange.getRequestHeaders().getFirst("Authorization");
          if ("Bearer synthetic-human-caller".equals(authorization)) {
            downstreamClient.set("human-caller");
          } else {
            var token = decoder.decode(authorization.substring("Bearer ".length()));
            downstreamClient.set(token.getClaimAsString("azp"));
          }
          requestTarget.set(exchange.getRequestURI().toString());
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          SyntheticTaskIssuer.reply(
              exchange,
              200,
              "POST".equals(exchange.getRequestMethod())
                  ? "{\"id\":88,\"name\":\"Org\"}"
                  : "{\"tenantId\":40,\"policies\":{}}");
        });
    issuer.server.createContext(
        "/internal/agencies",
        exchange -> {
          downstreamCalls.incrementAndGet();
          var token =
              decoder.decode(
                  exchange
                      .getRequestHeaders()
                      .getFirst("Authorization")
                      .substring("Bearer ".length()));
          downstreamClient.set(token.getClaimAsString("azp"));
          requestTarget.set(exchange.getRequestURI().toString());
          SyntheticTaskIssuer.reply(
              exchange, 200, "{\"agencyId\":9,\"tenantId\":7,\"name\":\"Centre\"}");
        });
  }

  @AfterEach
  void cleanup() {
    issuer.close();
  }

  @Test
  void reservedTenantCreationUsesSetupAndRetainsTheReservationOnTheWire() {
    var client = new TenantCreationClient(headers, legacy, identity, tenantFactory);
    var result =
        client.createTenant(
            new MultilingualTenantDTO()
                .id(88L)
                .name("Org")
                .tenantIdReservationToken("synthetic-reservation"));
    assertThat(result.getId()).isEqualTo(88L);
    assertThat(downstreamClient.get()).isEqualTo("backend-setup");
    assertThat(requestBody.get())
        .contains("\"id\":88", "\"tenantIdReservationToken\":\"synthetic-reservation\"");
    assertThat(downstreamCalls.get()).isEqualTo(1);
    assertThat(legacy.calls.get()).isZero();
  }

  @Test
  void notificationContactReadUsesMailAndRetainsTheExactTenantAndAgency() {
    var details = contacts().read(9, 7);
    assertThat(details.tenantId()).isEqualTo(7L);
    assertThat(downstreamClient.get()).isEqualTo("backend-mail");
    assertThat(requestTarget.get()).isEqualTo("/internal/agencies/9/contact-details?tenantId=7");
    assertThat(issuer.calls.get()).isEqualTo(1);
    assertThat(legacy.calls.get()).isZero();
  }

  @Test
  void scheduledPolicyReadUsesRuntimeAndRetainsTheRequestedTenant() {
    var result = policies().getTenantPermissionPolicies(40L);
    assertThat(result.getTenantId()).isEqualTo(40L);
    assertThat(downstreamClient.get()).isEqualTo("backend-runtime");
    assertThat(requestTarget.get()).isEqualTo("/tenantadmin/40/permission-policies");
    assertThat(issuer.calls.get()).isEqualTo(1);
    assertThat(legacy.calls.get()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"setup", "mail", "runtime"})
  void invalidActorTokenPreventsDownstreamCallsWithoutLegacyOrHumanFallback(String operation) {
    issuer.variant = "wrong-subject";
    caller.setAccessToken("synthetic-human-caller");
    assertThatThrownBy(
            () -> {
              switch (operation) {
                case "setup" ->
                    new TenantCreationClient(headers, legacy, identity, tenantFactory)
                        .createTenant(
                            new MultilingualTenantDTO()
                                .id(88L)
                                .name("Org")
                                .tenantIdReservationToken("synthetic-reservation"));
                case "mail" -> contacts().read(9, 7);
                default -> policies().getTenantPermissionPolicies(40L);
              }
            })
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause();
    assertThat(issuer.calls.get()).isEqualTo(1);
    assertThat(downstreamCalls.get()).isZero();
    assertThat(legacy.calls.get()).isZero();
  }

  @Test
  void missingReservationRefusesBeforeTokenOrTenantHttp() {
    assertThatThrownBy(
            () ->
                new TenantCreationClient(headers, legacy, identity, tenantFactory)
                    .createTenant(new MultilingualTenantDTO().id(88L).name("Org")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(issuer.calls.get()).isZero();
    assertThat(downstreamCalls.get()).isZero();
    assertThat(legacy.calls.get()).isZero();
  }

  @Test
  void humanTriggeredPolicyReadKeepsTheCallerTokenAndNeverIssuesAnActorGrant() {
    caller.setAccessToken("synthetic-human-caller");
    policies().getTenantPermissionPoliciesAsCaller(40L);
    assertThat(downstreamClient.get()).isEqualTo("human-caller");
    assertThat(issuer.calls.get()).isZero();
    assertThat(legacy.calls.get()).isZero();
  }

  private AgencyContactDetailsClient contacts() {
    var client = new AgencyContactDetailsClient(new RestTemplate(), legacy, identity, headers);
    ReflectionTestUtils.setField(client, "agencyServiceUrl", issuer.base());
    return client;
  }

  private TenantCaseHandoverPolicyReadClient policies() {
    return new TenantCaseHandoverPolicyReadClient(tenantFactory, legacy, identity, headers);
  }

  @Test
  void isolatedStagedSpringWiringIssuesTheActorGrantWithoutBeanRecursion() {
    var properties = new java.util.ArrayList<String>();
    properties.add("TASK_IDENTITY_MODEL=FIVE_ACTORS");
    properties.add("multitenancy.enabled=true");
    properties.add("keycloak.config.app-client-id=app");
    properties.add("synthetic.issuer.base=" + issuer.base());
    var bindings = FiveActorConfigurationTest.environment();
    for (TaskActor actor : TaskActor.values()) {
      for (String field : java.util.List.of("CLIENT_ID", "CLIENT_SECRET", "SERVICE_SUBJECT")) {
        String key = "IDENTITY_" + actor.name() + "_" + field;
        properties.add(key + "=" + bindings.getProperty(key));
      }
    }
    new ApplicationContextRunner()
        .withUserConfiguration(
            StagedBeans.class,
            TaskAwareIdentityClientConfig.class,
            TaskActorConfiguration.class,
            TaskActorTokenBroker.class,
            KeycloakAuthClient.class)
        .withPropertyValues(properties.toArray(String[]::new))
        .run(
            application -> {
              assertThat(application).hasNotFailed();
              var config = application.getBean(IdentityClientConfig.class);
              assertThat(
                      RoutineTaskAuthentication.accessToken(
                              TaskPurpose.INVITE_RESERVATIONS, config, legacy)
                          .isBlank())
                  .isFalse();
              assertThat(issuer.lastForm.get("client_id")).isEqualTo("backend-setup");
              assertThat(legacy.calls.get()).isZero();
            });
  }

  /** The production preflight is tested separately; this context is an isolated source fixture. */
  // Explicit runner registration only; AppConfig also scans test component stereotypes.
  static class StagedBeans {
    @Bean
    IdentityConfig legacyConfiguration(Environment environment) {
      var configuration = new IdentityConfig();
      configuration.setOpenidConnectUrl(environment.getRequiredProperty("synthetic.issuer.base"));
      return configuration;
    }

    @Bean
    KeycloakCustomConfig adminConfiguration() {
      var configuration = new KeycloakCustomConfig();
      configuration.setAppClientId("app");
      configuration.setAdminClientId("legacy-admin");
      return configuration;
    }

    @Bean
    KeycloakConfig issuerConfiguration(Environment environment) {
      var configuration = new KeycloakConfig();
      configuration.setAuthServerUrl(environment.getRequiredProperty("synthetic.issuer.base"));
      configuration.setRealm("synthetic");
      return configuration;
    }

    @Bean
    JwtDecoder taskDecoder(Environment environment) {
      return NimbusJwtDecoder.withJwkSetUri(
              environment.getRequiredProperty("synthetic.issuer.base") + "/certs")
          .build();
    }

    @Bean
    RestTemplate tokenHttp() {
      return new RestTemplate();
    }

    @Bean
    AuthenticatedUser human() {
      return new AuthenticatedUser();
    }
  }

  static final class NoLegacyAuthentication implements IdentityAuthentication {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public IdentityLogin loginService(String client, String secret) {
      calls.incrementAndGet();
      throw new AssertionError("Grouped caller must not request a legacy backend grant");
    }

    @Override
    public IdentityLogin login(String user, String password) {
      throw new AssertionError("Grouped caller must not request a human grant");
    }

    @Override
    public boolean logout(String token) {
      throw new AssertionError("Confidential grant has no refresh session");
    }

    @Override
    public boolean verifyPasswordIgnoringSecondFactor(String user, String password) {
      throw new AssertionError("Grouped caller must not verify a human password");
    }
  }
}
