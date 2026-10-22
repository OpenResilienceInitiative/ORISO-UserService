package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class KeycloakIdentitySessionExchangeTest {

  private static final String TOKEN_URL = "https://identity.example/token";

  @Mock private RestTemplate restTemplate;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private JwtDecoder jwtDecoder;
  @Mock private TaskActorConfiguration taskActors;
  @Mock private TaskActorTokenBroker broker;
  @Mock private ObjectProvider<AccountLifecycleCodec> lifecycleCodec;

  private KeycloakIdentitySessionExchange exchange;

  @BeforeEach
  void setUp() {
    exchange =
        new KeycloakIdentitySessionExchange(
            restTemplate, identityClientConfig, jwtDecoder, taskActors, broker, lifecycleCodec);
    ReflectionTestUtils.setField(exchange, "keycloakAdminClientId", "backend-admin");
    ReflectionTestUtils.setField(exchange, "keycloakAdminClientSecret", "secret");
    ReflectionTestUtils.setField(exchange, "keycloakAdminServiceSubject", "admin-service-subject");
    ReflectionTestUtils.setField(exchange, "keycloakAppClientId", "app");
    org.mockito.Mockito.lenient()
        .when(identityClientConfig.getOpenIdConnectUrl("/token"))
        .thenReturn(TOKEN_URL);
  }

  @Test
  void exchangeForUserShouldMapProviderResponseAndKeepGrantFieldsInsideAdapter() {
    var providerResponse =
        new KeycloakLoginResponseDTO(
            "access-token", 300, 600, "refresh-token", "Bearer", "session-state", "openid profile");
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", "admin-token")));
    when(jwtDecoder.decode("admin-token"))
        .thenReturn(
            adminToken("admin-service-subject", "backend-admin", List.of("otp-config-admin")));
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(providerResponse));

    var result = exchange.exchangeForUser("identity-user-id");

    assertThat(result)
        .hasValueSatisfying(
            session -> {
              assertThat(session.accessToken()).isEqualTo("access-token");
              assertThat(session.expiresIn()).isEqualTo(300);
              assertThat(session.refreshExpiresIn()).isEqualTo(600);
              assertThat(session.refreshToken()).isEqualTo("refresh-token");
              assertThat(session.tokenType()).isEqualTo("Bearer");
              assertThat(session.sessionState()).isEqualTo("session-state");
              assertThat(session.scope()).isEqualTo("openid profile");
            });

    var adminRequest = requestCaptor();
    verify(restTemplate).postForEntity(eq(TOKEN_URL), adminRequest.capture(), eq(Map.class));
    assertThat(form(adminRequest).getFirst("grant_type")).isEqualTo("client_credentials");
    assertThat(form(adminRequest).getFirst("client_id")).isEqualTo("backend-admin");
    assertThat(form(adminRequest).getFirst("client_secret")).isEqualTo("secret");
    assertThat(form(adminRequest)).doesNotContainKeys("username", "password");

    var exchangeRequest = requestCaptor();
    verify(restTemplate)
        .postForEntity(
            eq(TOKEN_URL), exchangeRequest.capture(), eq(KeycloakLoginResponseDTO.class));
    assertThat(form(exchangeRequest).getFirst("grant_type"))
        .isEqualTo("urn:ietf:params:oauth:grant-type:token-exchange");
    assertThat(form(exchangeRequest).getFirst("client_id")).isEqualTo("app");
    assertThat(form(exchangeRequest).getFirst("subject_token")).isEqualTo("admin-token");
    assertThat(form(exchangeRequest).getFirst("requested_subject")).isEqualTo("identity-user-id");
  }

  @Test
  void exchangeForUserShouldReturnEmptyWhenAdminLoginHasNoToken() {
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()));

    assertThat(exchange.exchangeForUser("identity-user-id")).isEmpty();

    verify(restTemplate, never())
        .postForEntity(eq(TOKEN_URL), any(), eq(KeycloakLoginResponseDTO.class));
  }

  @Test
  void exchangeForUserShouldReturnEmptyWhenProviderExchangeFails() {
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", "admin-token")));
    when(jwtDecoder.decode("admin-token"))
        .thenReturn(
            adminToken("admin-service-subject", "backend-admin", List.of("otp-config-admin")));
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(KeycloakLoginResponseDTO.class)))
        .thenThrow(new IllegalStateException("identity provider unavailable"));

    assertThat(exchange.exchangeForUser("identity-user-id")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrong-subject",
        "wrong-azp",
        "expired",
        "missing-role",
        "technical",
        "realm-admin",
        "resource-realm-admin",
        "unexpected-management-role",
        "foreign-resource"
      })
  void exchangeForUserShouldRejectUnexpectedAdminToken(String invalidClaim) {
    when(restTemplate.postForEntity(eq(TOKEN_URL), any(), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", "admin-token")));
    var subject = "wrong-subject".equals(invalidClaim) ? "other" : "admin-service-subject";
    var azp = "wrong-azp".equals(invalidClaim) ? "other" : "backend-admin";
    var roles =
        switch (invalidClaim) {
          case "missing-role" -> List.of("view-users");
          case "technical" -> List.of("otp-config-admin", "technical");
          case "realm-admin" -> List.of("otp-config-admin", "realm-admin");
          default -> List.of("otp-config-admin");
        };
    var expiry =
        "expired".equals(invalidClaim)
            ? Instant.now().minusSeconds(1)
            : Instant.now().plusSeconds(300);
    var managementRoles =
        switch (invalidClaim) {
          case "resource-realm-admin" ->
              List.of("manage-users", "view-users", "query-users", "view-realm", "realm-admin");
          case "unexpected-management-role" ->
              List.of("manage-users", "view-users", "query-users", "view-realm", "impersonation");
          default -> List.of("manage-users", "view-users", "query-users", "view-realm");
        };
    var resourceAccess =
        "foreign-resource".equals(invalidClaim)
            ? Map.of(
                "realm-management", Map.of("roles", managementRoles),
                "account", Map.of("roles", List.of("manage-account")))
            : Map.of("realm-management", Map.of("roles", managementRoles));
    when(jwtDecoder.decode("admin-token"))
        .thenReturn(adminToken(subject, azp, roles, resourceAccess, expiry));

    assertThat(exchange.exchangeForUser("identity-user-id")).isEmpty();

    verify(restTemplate, never())
        .postForEntity(eq(TOKEN_URL), any(), eq(KeycloakLoginResponseDTO.class));
  }

  private static Jwt adminToken(String subject, String azp, List<String> roles) {
    return adminToken(subject, azp, roles, Instant.now().plusSeconds(300));
  }

  private static Jwt adminToken(String subject, String azp, List<String> roles, Instant expiresAt) {
    return adminToken(
        subject,
        azp,
        roles,
        Map.of(
            "realm-management",
            Map.of("roles", List.of("manage-users", "view-users", "query-users", "view-realm"))),
        expiresAt);
  }

  private static Jwt adminToken(
      String subject,
      String azp,
      List<String> roles,
      Map<String, Map<String, List<String>>> resourceAccess,
      Instant expiresAt) {
    return Jwt.withTokenValue("admin-token")
        .header("alg", "none")
        .subject(subject)
        .claim("azp", azp)
        .claim("realm_access", Map.of("roles", roles))
        .claim("resource_access", resourceAccess)
        .issuedAt(Instant.now().minusSeconds(10))
        .expiresAt(expiresAt)
        .build();
  }

  @SuppressWarnings("rawtypes")
  private static ArgumentCaptor<HttpEntity> requestCaptor() {
    return ArgumentCaptor.forClass(HttpEntity.class);
  }

  @SuppressWarnings("unchecked")
  private static MultiValueMap<String, String> form(ArgumentCaptor<HttpEntity> captor) {
    return (MultiValueMap<String, String>) captor.getValue().getBody();
  }

  private static final String MAGIC_ISSUER = "https://identity.example/realms/c-fixture";
  private static final String MAGIC_URL = MAGIC_ISSUER + "/protocol/openid-connect/token";
  private static final String MAGIC_TARGET = "11111111-1111-4111-8111-111111111111";
  private static final String MAGIC_REALM_ID = "22222222-2222-4222-8222-222222222222";

  @Test
  void fiveActorsGenericSubjectExchangeIsClosedBeforeAnyGrant() {
    when(taskActors.isEnabled()).thenReturn(true);
    assertThat(exchange.exchangeForUser(MAGIC_TARGET)).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(restTemplate, broker, jwtDecoder);
  }

  @Test
  void liveClaimUsesTheFixedNativePasswordRouteAndIndependentProofMac() throws Exception {
    var http = new RestTemplate();
    var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(http).build();
    var configured = scopedTransport(http);
    var claim = liveTransportClaim();
    when(jwtDecoder.decode("native-access")).thenReturn(magicJwt(MAGIC_TARGET, "app", "42"));
    server
        .expect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(MAGIC_URL))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                org.springframework.http.HttpMethod.POST))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                "Authorization", "Bearer checked-admin"))
        .andExpect(
            request -> {
              assertThat(
                      request
                          .getHeaders()
                          .getContentType()
                          .isCompatibleWith(
                              org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED))
                  .isTrue();
              String raw =
                  ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                      .getBodyAsString();
              var fields = new java.util.TreeMap<String, String>();
              for (String field : raw.split("&")) {
                String[] pair = field.split("=", 2);
                fields.put(
                    java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8));
              }
              assertThat(fields.keySet())
                  .containsExactly("client_id", "grant_type", "oriso_magic_command");
              assertThat(fields.get("client_id")).isEqualTo("app");
              assertThat(fields.get("grant_type")).isEqualTo("password");
              var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
              var body = mapper.readTree(fields.get("oriso_magic_command"));
              assertThat(body.size()).isEqualTo(7);
              assertThat(body.path("targetUserId").asText()).isEqualTo(MAGIC_TARGET);
              assertThat(body.path("workflowId").asText()).isEqualTo(claim.workflowId().toString());
              assertThat(body.path("tenantId").isIntegralNumber()).isTrue();
              assertThat(body.path("tenantId").longValue()).isEqualTo(42L);
              assertThat(body.path("clientId").asText()).isEqualTo("app");
              assertThat(body.path("expiresAt").longValue())
                  .isEqualTo(claim.expiresAt().getEpochSecond());
              assertThat(body.path("claimDigest").asText()).isEqualTo(claim.claimDigest());
              assertThat(body.path("version").intValue()).isEqualTo(1);
              String[] signed = request.getHeaders().getFirst("X-ORISO-Task-Context").split("\\.");
              assertThat(signed).hasSize(2);
              var claims = mapper.readTree(java.util.Base64.getUrlDecoder().decode(signed[0]));
              assertThat(claims.size()).isEqualTo(15);
              assertThat(claims.path("iss").asText()).isEqualTo("userservice");
              assertThat(claims.path("aud").asText()).isEqualTo("oriso-session-exchange");
              assertThat(claims.path("purpose").asText()).isEqualTo("SESSION_EXCHANGE");
              assertThat(claims.path("operation").asText())
                  .isEqualTo("session.magic-link.exchange");
              assertThat(claims.path("method").asText()).isEqualTo("POST");
              assertThat(claims.path("actor").asText()).isEqualTo("ACCOUNT_ADMIN");
              assertThat(claims.path("realm").asText()).isEqualTo("c-fixture");
              assertThat(claims.path("target").asText())
                  .isEqualTo("realm/" + MAGIC_REALM_ID + "/magic-link/" + claim.workflowId());
              assertThat(claims.path("taskClient").asText()).isEqualTo("backend-account-admin");
              assertThat(claims.path("taskSubject").asText())
                  .isEqualTo("33333333-3333-4333-8333-333333333333");
              assertThat(java.util.UUID.fromString(claims.path("jti").asText()).toString())
                  .isEqualTo(claims.path("jti").asText());
              assertThat(claims.path("nonce").asText()).matches("[0-9a-f]{32}");
              assertThat(claims.path("iat").isIntegralNumber()).isTrue();
              assertThat(claims.path("exp").longValue())
                  .isGreaterThan(claims.path("iat").longValue())
                  .isLessThanOrEqualTo(claims.path("iat").longValue() + 30)
                  .isLessThanOrEqualTo(claim.expiresAt().getEpochSecond());
              var sortedBody =
                  mapper.convertValue(
                      body,
                      new com.fasterxml.jackson.core.type.TypeReference<
                          java.util.TreeMap<String, Object>>() {});
              try {
                var mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(new byte[32], "HmacSHA256"));
                String digest =
                    java.util.HexFormat.of()
                        .formatHex(
                            mac.doFinal(
                                ("magic-link-command/v1\n" + mapper.writeValueAsString(sortedBody))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                assertThat(claims.path("payloadDigest").asText()).isEqualTo(digest);
                mac.init(new javax.crypto.spec.SecretKeySpec(new byte[32], "HmacSHA256"));
                assertThat(java.util.Base64.getUrlDecoder().decode(signed[1]))
                    .isEqualTo(
                        mac.doFinal(
                            ("magic-link-proof/v1\n" + signed[0])
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
              } catch (java.security.GeneralSecurityException failure) {
                throw new AssertionError(failure);
              }
            })
        .andRespond(
            org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                "{\"access_token\":\"native-access\",\"expires_in\":60,\"refresh_expires_in\":600,\"refresh_token\":\"refresh\",\"token_type\":\"Bearer\",\"session_state\":\"44444444-4444-4444-8444-444444444444\",\"scope\":\"openid\"}",
                org.springframework.http.MediaType.APPLICATION_JSON));
    assertThat(configured.exchangeMagicLink(claim))
        .hasValueSatisfying(result -> assertThat(result.accessToken()).isEqualTo("native-access"));
    server.verify();
    verify(broker).issueCheckedSessionExchange();
    org.mockito.Mockito.verifyNoInteractions(restTemplate);
  }

  @Test
  void absentClosedAndAmbientSqlClaimsNeverReachTheSessionBroker() {
    var configured = scopedTransport(new RestTemplate());
    assertThat(configured.exchangeMagicLink(null)).isEmpty();
    var closed = liveTransportClaim();
    org.mockito.Mockito.lenient()
        .doThrow(new IllegalStateException("closed"))
        .when(closed)
        .requireCurrent();
    assertThat(configured.exchangeMagicLink(closed)).isEmpty();
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThat(configured.exchangeMagicLink(liveTransportClaim())).isEmpty();
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
    org.mockito.Mockito.verifyNoInteractions(broker, restTemplate, jwtDecoder);
  }

  @Test
  void fixedSessionProofRefusesForeignPurposesAndExpiredCheckedTokens() {
    var codec = new AccountLifecycleCodec(new byte[32]);
    var body = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    body.put("version", 1)
        .put("workflowId", "55555555-5555-4555-8555-555555555555")
        .put("targetUserId", MAGIC_TARGET)
        .put("tenantId", 42)
        .put("clientId", "app")
        .put("expiresAt", Instant.now().plusSeconds(90).getEpochSecond())
        .put("claimDigest", "b".repeat(64));
    var actor = checkedSessionActor();
    when(actor.purpose()).thenReturn(de.caritas.cob.userservice.api.config.auth.TaskPurpose.OTP);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> codec.magicLinkProof(actor, "c-fixture", MAGIC_REALM_ID, body))
        .isInstanceOf(IllegalStateException.class);
    when(actor.purpose())
        .thenReturn(de.caritas.cob.userservice.api.config.auth.TaskPurpose.SESSION_EXCHANGE);
    when(actor.validatedJwtExpiresAt()).thenReturn(Instant.now().minusSeconds(1).getEpochSecond());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> codec.magicLinkProof(actor, "c-fixture", MAGIC_REALM_ID, body))
        .isInstanceOf(IllegalStateException.class);
  }

  private KeycloakIdentitySessionExchange scopedTransport(RestTemplate http) {
    org.mockito.Mockito.lenient().when(taskActors.isEnabled()).thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(identityClientConfig.getOpenIdConnectUrl("/token"))
        .thenReturn(MAGIC_URL);
    org.mockito.Mockito.lenient()
        .when(lifecycleCodec.getObject())
        .thenReturn(new AccountLifecycleCodec(new byte[32]));
    org.mockito.Mockito.lenient()
        .when(broker.issueCheckedSessionExchange())
        .thenReturn(checkedSessionActor());
    var result =
        new KeycloakIdentitySessionExchange(
            http, identityClientConfig, jwtDecoder, taskActors, broker, lifecycleCodec);
    ReflectionTestUtils.setField(result, "keycloakAppClientId", "app");
    ReflectionTestUtils.setField(result, "realmName", "c-fixture");
    ReflectionTestUtils.setField(result, "realmId", MAGIC_REALM_ID);
    return result;
  }

  private TaskActorTokenBroker.VerifiedMaintenanceToken checkedSessionActor() {
    var result = org.mockito.Mockito.mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    org.mockito.Mockito.lenient()
        .when(result.purpose())
        .thenReturn(de.caritas.cob.userservice.api.config.auth.TaskPurpose.SESSION_EXCHANGE);
    org.mockito.Mockito.lenient()
        .when(result.actor())
        .thenReturn(de.caritas.cob.userservice.api.config.auth.TaskActor.ACCOUNT_ADMIN);
    org.mockito.Mockito.lenient().when(result.accessToken()).thenReturn("checked-admin");
    org.mockito.Mockito.lenient().when(result.issuer()).thenReturn(MAGIC_ISSUER);
    org.mockito.Mockito.lenient().when(result.clientId()).thenReturn("backend-account-admin");
    org.mockito.Mockito.lenient()
        .when(result.subject())
        .thenReturn("33333333-3333-4333-8333-333333333333");
    org.mockito.Mockito.lenient()
        .when(result.validatedJwtIssuedAt())
        .thenReturn(Instant.now().minusSeconds(1).getEpochSecond());
    org.mockito.Mockito.lenient()
        .when(result.validatedJwtExpiresAt())
        .thenReturn(Instant.now().plusSeconds(120).getEpochSecond());
    return result;
  }

  private de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService.ClaimedMagicLink
      liveTransportClaim() {
    var result =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService.ClaimedMagicLink
                .class);
    org.mockito.Mockito.lenient().when(result.targetUserId()).thenReturn(MAGIC_TARGET);
    org.mockito.Mockito.lenient()
        .when(result.workflowId())
        .thenReturn(java.util.UUID.fromString("55555555-5555-4555-8555-555555555555"));
    org.mockito.Mockito.lenient().when(result.tenantId()).thenReturn(42L);
    org.mockito.Mockito.lenient()
        .when(result.expiresAt())
        .thenReturn(
            Instant.now().plusSeconds(90).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    org.mockito.Mockito.lenient().when(result.claimDigest()).thenReturn("b".repeat(64));
    return result;
  }

  private Jwt magicJwt(String subject, String client, Object tenant) {
    return Jwt.withTokenValue("native-access")
        .header("alg", "RS256")
        .issuer(MAGIC_ISSUER)
        .subject(subject)
        .issuedAt(Instant.now().minusSeconds(1))
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("azp", client)
        .claim("tenantId", tenant)
        .claim("sid", "44444444-4444-4444-8444-444444444444")
        .build();
  }
}
