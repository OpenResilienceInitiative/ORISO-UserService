package de.caritas.cob.userservice.api.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakIdentitySessionExchange;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/** Explicit-only hosted gate. Real Redis/native HTTP; the account SQL lookup is a finite double. */
class MagicLinkSessionNativeFixture {
  @Test
  void actualReplicaClaimRetriesTheSamePrivateWorkflowAfterLostNativeResponse() throws Exception {
    var redis =
        new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(required("S1_REDIS_PORT")));
    redis.afterPropertiesSet();
    redis.start();
    try {
      var template = new StringRedisTemplate(redis);
      template.afterPropertiesSet();
      String prefix = "s1:private:" + UUID.randomUUID() + ":";
      var first = new RedisOneTimeTokenStore(template, prefix);
      // The second instance uses exactly the same durable namespace, like another User replica.
      var second = new RedisOneTimeTokenStore(template, prefix);
      var codec = new AccountLifecycleCodec(Base64.getDecoder().decode(required("S1_CONTEXT_KEY")));
      String token = UUID.randomUUID().toString() + UUID.randomUUID();
      UUID workflow = UUID.randomUUID();
      var held =
          new OneTimeTokenStore.TokenClaim(
              required("S1_TARGET_USER"),
              Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.SECONDS),
              workflow,
              Long.valueOf(required("S1_TARGET_TENANT")),
              codec.digest(
                  "magic-link-capability/v1",
                  Map.of("workflowId", workflow.toString(), "token", token)));
      first.storeMagicLink(token, held);
      assertThat(second.claim("magic-login", token)).contains(held);
      assertThat(first.claim("magic-login", token)).isEmpty();
      assertThat(second.restore("magic-login", token, held, false)).isTrue();
      assertThat(first.claim("magic-login", token)).contains(held);
      assertThat(second.restore("magic-login", token, held, false)).isTrue();

      String base = required("S1_NATIVE_BASE"), realm = required("S1_REALM_NAME");
      String issuer = base + "/realms/" + realm;
      var identity = new IdentityConfig();
      identity.setOpenidConnectUrl(issuer + "/protocol/openid-connect");
      var environment = new MockEnvironment().withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS");
      var bindings = new ObjectMapper().readTree(required("S1_ACTOR_BINDINGS"));
      for (var actor : TaskActor.values()) {
        var binding = bindings.get(actor.name());
        environment.setProperty(
            "IDENTITY_" + actor.name() + "_CLIENT_ID", binding.get("clientId").textValue());
        environment.setProperty(
            "IDENTITY_" + actor.name() + "_CLIENT_SECRET", binding.get("secret").textValue());
        environment.setProperty(
            "IDENTITY_" + actor.name() + "_SERVICE_SUBJECT", binding.get("subject").textValue());
      }
      var legacy = new KeycloakCustomConfig();
      legacy.setAppClientId("app");
      var actors = new TaskActorConfiguration(environment, legacy, identity);
      var nativeHttp =
          new RestTemplate(
              new JdkClientHttpRequestFactory(
                  HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()));
      var authentication = new KeycloakAuthClient(nativeHttp, new AuthenticatedUser(), identity);
      ReflectionTestUtils.setField(authentication, "keycloakClientId", "app");
      var keycloak = new KeycloakConfig();
      keycloak.setAuthServerUrl(base);
      keycloak.setRealm(realm);
      var decoder =
          NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
      var broker = new TaskActorTokenBroker(actors, authentication, decoder, keycloak);
      // The fixed URL stays the native issuer. An actual local HTTP proxy drops the first
      // upstream200 body; this is not a fake JWT, copied bearer or manufactured Java handle.
      var requestFactory =
          new JdkClientHttpRequestFactory(
              HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(5))
                  .proxy(
                      ProxySelector.of(
                          new InetSocketAddress(
                              "127.0.0.1", Integer.parseInt(required("S1_PROXY_PORT")))))
                  .build());
      requestFactory.setReadTimeout(Duration.ofSeconds(15));
      var beans = new DefaultListableBeanFactory();
      beans.registerSingleton("s1Codec", codec);
      var provider = beans.getBeanProvider(AccountLifecycleCodec.class);
      var exchange =
          new KeycloakIdentitySessionExchange(
              new RestTemplate(requestFactory), identity, decoder, actors, broker, provider);
      ReflectionTestUtils.setField(exchange, "keycloakAppClientId", "app");
      ReflectionTestUtils.setField(exchange, "realmName", realm);
      ReflectionTestUtils.setField(exchange, "realmId", required("S1_REALM_ID"));
      var service =
          new MagicLinkLoginService(
              mock(UserService.class),
              mock(ConsultantService.class),
              exchange,
              first,
              mock(ApplicationSettingsService.class),
              mock(OrisoEmailRenderer.class),
              mock(OrisoEmailBrand.class),
              actors,
              provider);
      assertThat(service.consumeMagicLink(token)).isEmpty();
      assertThat(second.claim("magic-login", token)).contains(held);
      assertThat(second.restore("magic-login", token, held, false)).isTrue();
      var result = service.consumeMagicLink(token).orElseThrow();
      assertThat(first.claim("magic-login", token)).isEmpty();
      var actual = decoder.decode(result.accessToken());
      assertThat(actual.getSubject()).isEqualTo(held.subjectId());
      assertThat(actual.getClaimAsString("azp")).isEqualTo("app");
      assertThat(actual.getClaimAsString("tenantId")).isEqualTo(Long.toString(held.tenantId()));
      assertThat(actual.getClaimAsString("sid")).isEqualTo(result.sessionState());
      assertThat(actual.getExpiresAt()).isAfter(Instant.now());
      assertThat(held.toString()).doesNotContain(token, held.subjectId(), held.capabilityDigest());
    } finally {
      redis.destroy();
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Native paired fixture is required");
    return value;
  }
}
