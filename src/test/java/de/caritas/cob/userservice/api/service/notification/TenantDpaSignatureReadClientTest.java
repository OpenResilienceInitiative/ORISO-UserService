package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskAwareIdentityClientConfig;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.notification.DpaAdminNoticeOutboxConsumer.Event;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TenantDpaSignatureReadClientTest {

  private static final String BASE_URL = "https://tenant.example.test/service/";
  private static final String OUTBOX =
      "https://tenant.example.test/service/internal/dpa-admin-notice-outbox";
  private static final String AUTH_BASE = "https://identity.example.test";
  private static final String ISSUER = AUTH_BASE + "/realms/example";
  private static final String CLIENT = "backend-mail";
  private static final String SUBJECT = "mail-subject";
  private static final Event EVENT = new Event(1704L, 23L, 1_763_546_400_123L);
  private static final SecretKeySpec KEY =
      new SecretKeySpec(
          "test-only-hmac-key-for-signed-task-fixture".getBytes(StandardCharsets.UTF_8),
          "HmacSHA256");

  private MockRestServiceServer server;
  private MockEnvironment environment;
  private TenantDpaSignatureReadClient client;
  private TaskActorConfiguration bindings;
  private IdentityAuthentication legacyLogin;
  private KeycloakAuthClient taskLogin;
  private final NimbusJwtDecoder receiver = NimbusJwtDecoder.withSecretKey(KEY).build();

  @BeforeEach
  void setup() throws JOSEException {
    var rest = new RestTemplate();
    server = MockRestServiceServer.bindTo(rest).build();
    environment = new MockEnvironment().withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS");
    bindings = mock(TaskActorConfiguration.class);
    taskLogin = mock(KeycloakAuthClient.class);
    legacyLogin = mock(IdentityAuthentication.class);
    var realm = new KeycloakConfig();
    realm.setAuthServerUrl(AUTH_BASE);
    realm.setRealm("example");
    when(bindings.require(TaskPurpose.NOTIFICATION_DISPATCH))
        .thenReturn(new TaskActorCredentials(TaskActor.MAIL, CLIENT, "fixture-password", SUBJECT));
    when(taskLogin.loginService(CLIENT, "fixture-password"))
        .thenReturn(
            new KeycloakLoginResponseDTO(signedToken(), 120, 0, null, "Bearer", null, null));
    var beans = new StaticListableBeanFactory();
    beans.addBean("taskBroker", new TaskActorTokenBroker(bindings, taskLogin, receiver, realm));
    var identity =
        new TaskAwareIdentityClientConfig(
            mock(IdentityConfig.class),
            environment,
            beans.getBeanProvider(TaskActorTokenBroker.class));
    var headers = new SecurityHeaderSupplier(new AuthenticatedUser());
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "csrfHeader");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "csrfCookie");
    client =
        new TenantDpaSignatureReadClient(
            headers,
            legacyLogin,
            identity,
            mock(TenantAdminServiceApiControllerFactory.class),
            rest);
    ReflectionTestUtils.setField(client, "tenantServiceApiUrl", BASE_URL);
  }

  @Test
  void dormantByDefaultAndRefusesLegacyCredentialsEvenWhenEnabled() {
    assertThatThrownBy(() -> client.readPending(0L, 50)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> client.readExact(EVENT)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> client.acknowledge(EVENT)).isInstanceOf(IllegalStateException.class);
    enable();
    environment.setProperty("TASK_IDENTITY_MODEL", "LEGACY");
    assertThatThrownBy(() -> client.readPending(0L, 50)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> client.readExact(EVENT)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> client.acknowledge(EVENT)).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(bindings, taskLogin, legacyLogin);
    server.verify();
  }

  @Test
  void sendsOnlyTheExactAcknowledgementAndRequiresTheDedicatedNoContentResult() {
    enable();
    String url = OUTBOX + "/1704/acknowledgements";
    String body = "{\"signedAtEpochMillis\":" + EVENT.signedAtEpochMillis() + "}";
    server
        .expect(requestTo(url))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(body))
        .andExpect(request -> assertTaskBearer(request.getHeaders()))
        .andRespond(withStatus(HttpStatus.ACCEPTED));
    server
        .expect(requestTo(url))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(body))
        .andExpect(request -> assertTaskBearer(request.getHeaders()))
        .andRespond(withNoContent());
    assertThatThrownBy(() -> client.acknowledge(EVENT))
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause();
    client.acknowledge(EVENT);
    verifyNoInteractions(legacyLogin);
    server.verify();
  }

  @Test
  void readsABoundedPageIncludingAnEmptyContinuationWithTheSignedMailActor() {
    enable();
    expectGet(
        OUTBOX + "?afterId=0&limit=100",
        """
        {"events":[{"id":1704,"tenantId":23,"signedAtEpochMillis":1763546400123}],"nextAfterId":1704}
        """);
    expectGet(
        OUTBOX + "?afterId=1704&limit=1",
        """
        {"events":[],"nextAfterId":1710}
        """);
    var first = client.readPending(0L, 500);
    assertThat(first.events()).containsExactly(EVENT);
    assertThat(first.nextAfterId()).isEqualTo(EVENT.id());
    var continuation = client.readPending(first.nextAfterId(), 0);
    assertThat(continuation.events()).isEmpty();
    assertThat(continuation.nextAfterId()).isEqualTo(1710L);
    verifyNoInteractions(legacyLogin);
    server.verify();
  }

  @Test
  void acceptsOnlyTheSameIdTenantAndSigningEpochInAnIndependentExactAuditRead() {
    enable();
    expectGet(OUTBOX + "/1704", audit(1704, 23, EVENT.signedAtEpochMillis()));
    expectGet(OUTBOX + "/1704", audit(1703, 23, EVENT.signedAtEpochMillis()));
    expectGet(OUTBOX + "/1704", audit(1704, 24, EVENT.signedAtEpochMillis()));
    expectGet(OUTBOX + "/1704", audit(1704, 23, EVENT.signedAtEpochMillis() + 1));
    expectGet(
        OUTBOX + "/1704",
        audit(1704, 23, EVENT.signedAtEpochMillis()).replace("2026-10-30T12:00", ""));
    var verified = client.readExact(EVENT).orElseThrow();
    assertThat(verified.event()).isEqualTo(EVENT);
    assertThat(verified.signature().getStatus()).isEqualTo("SIGNED");
    assertThat(verified.signature().getSource()).isEqualTo("FORWARDED_EXTERNAL");
    assertThat(verified.signature().getDpaVersion()).isEqualTo("2026-10-30T12:00");
    assertThat(verified.signature().getSignerEmail()).isNull();
    assertThat(client.readExact(EVENT)).isEmpty();
    assertThat(client.readExact(EVENT)).isEmpty();
    assertThat(client.readExact(EVENT)).isEmpty();
    assertThat(client.readExact(EVENT)).isEmpty();
    verifyNoInteractions(legacyLogin);
    server.verify();
  }

  @Test
  void rejectsSignedTokensOutsideTheConfiguredMailPurposeBeforeCallingTenantService()
      throws JOSEException {
    enable();
    when(taskLogin.loginService(CLIENT, "fixture-password"))
        .thenReturn(
            new KeycloakLoginResponseDTO(
                signedToken(TaskActor.RUNTIME), 120, 0, null, "Bearer", null, null));
    assertThatThrownBy(() -> client.readPending(0, 25))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("authentication unavailable");
    verifyNoInteractions(legacyLogin);
    server.verify();
  }

  @Test
  void rejectsInvalidPageProgressAndUntrustedOrUnavailableAuditWithoutLeakingDetails() {
    enable();
    expectGet(
        OUTBOX + "?afterId=10&limit=25",
        """
        {"events":[],"nextAfterId":10}
        """);
    server
        .expect(requestTo(OUTBOX + "/1704"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withResourceNotFound());
    server
        .expect(requestTo(OUTBOX + "/1704"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).body("private diagnostic"));
    assertThatThrownBy(() -> client.readPending(10, 25)).isInstanceOf(IllegalStateException.class);
    assertThat(client.readExact(EVENT)).isEmpty();
    assertThatThrownBy(() -> client.readExact(EVENT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TenantService exact DPA signature is unavailable (HTTP 403)")
        .hasNoCause();
    verifyNoInteractions(legacyLogin);
    server.verify();
  }

  @Test
  void retainsSafeStatusAndTransportDiagnosticsForPullAndAcknowledgement() {
    enable();
    server
        .expect(requestTo(OUTBOX + "?afterId=0&limit=8"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("private provider body"));
    server
        .expect(requestTo(OUTBOX + "/1704/acknowledgements"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withException(new IOException("private connection details")));

    assertThatThrownBy(() -> client.readPending(0, 8))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TenantService DPA outbox page is unavailable (HTTP 503)")
        .hasNoCause();
    assertThatThrownBy(() -> client.acknowledge(EVENT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TenantService DPA acknowledgement is unavailable (transport failure)")
        .hasNoCause();
    verifyNoInteractions(legacyLogin);
    server.verify();
  }

  private void enable() {
    ReflectionTestUtils.setField(client, "outboxReadEnabled", true);
  }

  private void expectGet(String url, String body) {
    server
        .expect(requestTo(url))
        .andExpect(method(HttpMethod.GET))
        .andExpect(request -> assertTaskBearer(request.getHeaders()))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
  }

  private void assertTaskBearer(HttpHeaders headers) {
    var authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
    assertThat(authorization).startsWith("Bearer ");
    var jwt = receiver.decode(authorization.substring("Bearer ".length()));
    assertThat(jwt.getSubject()).isEqualTo(SUBJECT);
    assertThat(jwt.getClaimAsString("azp")).isEqualTo(CLIENT);
    assertThat(jwt.getAudience()).contains("tenantservice");
    assertThat(jwt.getClaimAsMap("realm_access")).containsKey("roles");
    verify(bindings, org.mockito.Mockito.atLeastOnce()).require(TaskPurpose.NOTIFICATION_DISPATCH);
  }

  private static String audit(long id, long tenantId, long signingEpoch) {
    return """
        {"id":%d,"tenantId":%d,"signedAtEpochMillis":%d,"dpaVersion":"2026-10-30T12:00",
         "signedAt":"2026-11-19T10:00","forwardedByUserId":"forwarder","signerName":"Synthetic signer",
         "signerPosition":"Director"}
        """
        .formatted(id, tenantId, signingEpoch);
  }

  private static String signedToken() throws JOSEException {
    return signedToken(TaskActor.MAIL);
  }

  private static String signedToken(TaskActor actor) throws JOSEException {
    var now = Instant.now();
    var jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(SUBJECT)
                .claim("azp", CLIENT)
                .audience(List.copyOf(actor.audiences()))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(120)))
                .claim("realm_access", Map.of("roles", List.copyOf(actor.roles())))
                .build());
    jwt.sign(new MACSigner(KEY));
    return jwt.serialize();
  }
}
