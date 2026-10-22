package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTenantDTO;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

class ReservedTenantBootstrapTest {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final UUID CLAIM = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID ATTEMPT = UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID EXECUTION = UUID.fromString("10000000-0000-0000-0000-000000000003");
  private final RestTemplate http = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.createServer(http);
  private final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
  private final JwtDecoder decoder = mock(JwtDecoder.class);
  private final AcceptanceClaim claim = mock(AcceptanceClaim.class);
  private final SecurityHeaderSupplier headers =
      new SecurityHeaderSupplier(mock(AuthenticatedUser.class));
  private final TenantCreationClient client =
      new TenantCreationClient(
          headers,
          mock(IdentityAuthentication.class),
          mock(IdentityClientConfig.class),
          new TenantAdminServiceApiControllerFactory());
  private JsonNode frozen;
  private byte[] key;
  private String setupSubject;
  private String capability;

  @BeforeEach
  void setup() throws Exception {
    frozen =
        JSON.readTree(
            getClass().getResourceAsStream("/scenario-c/protected-approval-reserved-v1.json"));
    key = HexFormat.of().parseHex(frozen.path("syntheticPublicKeyHex").asText());
    setupSubject = frozen.path("request").path("scope").path("setupSubject").asText();
    capability = frozen.path("syntheticReservationToken").asText();
    when(claim.frozen())
        .thenReturn(
            new FrozenApproval(
                UUID.fromString(frozen.path("approvalId").asText()),
                frozen.path("request").toString(),
                frozen.path("issuerRealm").asText(),
                frozen.path("issuerSubject").asText(),
                frozen.path("requestDigest").asText()));
    when(claim.claimId()).thenReturn(CLAIM);
    when(claim.attemptId()).thenReturn(ATTEMPT);
    when(claim.executionId()).thenReturn(EXECUTION);
    when(claim.journalVersion()).thenReturn(1L);
    when(broker.issue(TaskPurpose.CONFIG_WIZARD))
        .thenReturn(new TaskActorTokenBroker.TaskToken(TaskActor.SETUP, "synthetic-setup", 60));
    when(decoder.decode("synthetic-setup"))
        .thenReturn(
            Jwt.withTokenValue("synthetic-setup")
                .header("alg", "RS256")
                .issuer("https://identity.example/realms/test")
                .subject(setupSubject)
                .claim("azp", "backend-setup")
                .audience(
                    java.util.List.of(
                        "tenantservice", "agencyservice", "consultingtypeservice", "userservice"))
                .claim(
                    "realm_access",
                    Map.of(
                        "roles",
                        java.util.List.of(
                            "config-wizard", "invitation-reservations", "consultant-import")))
                .issuedAt(Instant.now().minusSeconds(1))
                .expiresAt(Instant.now().plusSeconds(60))
                .build());
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "X-CSRF-TOKEN");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "CSRF-TOKEN");
    var environment =
        new MockEnvironment()
            .withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS")
            .withProperty("ORISO_TASK_CONTEXT_KEY", Base64.getEncoder().encodeToString(key));
    ReflectionTestUtils.setField(
        client,
        "reservedTenantBootstrap",
        new ReservedTenantBootstrap(
            http, headers, broker, decoder, environment, "https://tenant.example"));
  }

  @AfterEach
  void cleanup() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void fixedPostAuthenticatesTheCompleteReplyAndKeepsPrivateResultDeepFrozen() throws Exception {
    MultilingualTenantDTO input = tenant();
    server
        .expect(requestTo("https://tenant.example/tenantadmin/bootstrap"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer synthetic-setup"))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andRespond(
            request -> {
              JsonNode sent = JSON.readTree(((MockClientHttpRequest) request).getBodyAsString());
              assertThat(sent.size()).isEqualTo(4);
              assertThat(sent.path("tenant").path("tenantIdReservationToken").asText())
                  .isEqualTo(capability);
              assertThat(sent.path("tenant").path("name").asText()).isEqualTo("Äctual tenant");
              return withSuccess(reply(sent, null), MediaType.APPLICATION_JSON)
                  .createResponse(request);
            });
    var result = client.createReservedTenant(claim, input);
    assertThat(result).isNotNull();
    assertThat(result.tenantId()).isEqualTo(input.getId());
    assertThat(result.claimId()).isEqualTo(CLAIM);
    assertThat(result.attemptId()).isEqualTo(ATTEMPT);
    assertThat(result.executionId()).isEqualTo(EXECUTION);
    assertThat(result.receipt()).isNotBlank();
    assertThat(result.tenant().getActive()).isTrue();
    assertThat(
            result
                .tenant()
                .getSettings()
                .getTenantAdminControls()
                .getAccountInactivitySettings()
                .getOtherMonths())
        .isEqualTo(18);
    result
        .tenant()
        .getSettings()
        .getTenantAdminControls()
        .getAccountInactivitySettings()
        .setOtherMonths(1);
    assertThat(
            result
                .tenant()
                .getSettings()
                .getTenantAdminControls()
                .getAccountInactivitySettings()
                .getOtherMonths())
        .isEqualTo(18);
    input.setName("changed");
    result.tenant().setName("also changed");
    assertThat(result.tenant().getName()).isEqualTo("Äctual tenant");
    assertThat(result.toString()).doesNotContain("capability", "Äctual", result.receipt());
    server.verify();
  }

  @Test
  void independentlyResignedForeignReceiptFieldsAndNonExactShapesRefuse() throws Exception {
    for (String mutation :
        new String[] {
          "claimId",
          "accountAttemptId",
          "executionId",
          "bootstrapAttemptId",
          "tenantId",
          "ownerSubject",
          "ownerClient",
          "ownerIssuer",
          "requestDigest",
          "resultDigest",
          "state",
          "v",
          "extra",
          "partial",
          "fractional"
        }) {
      server.reset();
      server
          .expect(anything())
          .andRespond(
              request ->
                  withSuccess(
                          reply(
                              JSON.readTree(((MockClientHttpRequest) request).getBodyAsString()),
                              mutation),
                          MediaType.APPLICATION_JSON)
                      .createResponse(request));
      assertThatThrownBy(() -> client.createReservedTenant(claim, tenant()))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              failure ->
                  assertThat(((ResponseStatusException) failure).getStatusCode())
                      .isEqualTo(HttpStatus.BAD_GATEWAY));
      server.verify();
    }
  }

  @Test
  void ambientSqlOrMissingHeldPairRefusesBeforeAuthenticationOrHttp() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.createReservedTenant(claim, tenant()))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.clear();
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> client.createReservedTenant(claim, tenant()))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.clear();
    assertThatThrownBy(
            () -> client.createReservedTenant(claim, tenant().tenantIdReservationToken(null)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(broker, decoder);
    server.verify();
  }

  @Test
  void unresolved503RetainsTheExactClaimAndNeverRetriesOrLeaksUpstreamText() {
    server
        .expect(anything())
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("synthetic-private-upstream-body"));
    assertThatThrownBy(() -> client.createReservedTenant(claim, tenant()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            failure ->
                assertThat(((ResponseStatusException) failure).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
        .hasMessageNotContaining("synthetic-private");
    verify(broker, times(1)).issue(TaskPurpose.CONFIG_WIZARD);
    server.verify();
  }

  private MultilingualTenantDTO tenant() {
    return new MultilingualTenantDTO()
        .id(frozen.path("request").path("scope").path("targetTenantIds").get(0).longValue())
        .name("Äctual tenant")
        .tenantIdReservationToken(capability);
  }

  @lombok.SneakyThrows
  private String reply(JsonNode sent, String mutation) {
    ObjectNode value = ((ObjectNode) sent.path("tenant")).deepCopy();
    value.remove("tenantIdReservationToken");
    value.put("active", true);
    value.set(
        "settings",
        JSON.readTree(
            "{\"emailVisible\":false,\"emailRequired\":false,\"tenantAdminControls\":{\"accountInactivitySettings\":{\"askerMonths\":12,\"consultantMonths\":15,\"otherMonths\":18,\"revision\":7}}}"));
    var fields = new TreeMap<String, Object>();
    fields.put("v", 1);
    fields.put("ownerIssuer", "https://identity.example/realms/test");
    fields.put("ownerClient", "backend-setup");
    fields.put("ownerSubject", setupSubject);
    fields.put("claimId", CLAIM.toString());
    fields.put("accountAttemptId", ATTEMPT.toString());
    fields.put("executionId", EXECUTION.toString());
    fields.put("bootstrapAttemptId", "10000000-0000-0000-0000-000000000004");
    fields.put("tenantId", value.path("id").longValue());
    fields.put("requestDigest", mac("tenant-held-bootstrap-request/v1\n" + canonical(sent)));
    fields.put("resultDigest", mac("tenant-held-bootstrap-result/v1\n" + canonical(value)));
    fields.put("state", "COMPLETE");
    if (mutation != null) {
      switch (mutation) {
        case "extra" -> fields.put("extra", false);
        case "partial" -> fields.remove("state");
        case "fractional" -> fields.put("tenantId", 1.5);
        case "tenantId" -> fields.put("tenantId", 999);
        case "v" -> fields.put("v", "1");
        case "bootstrapAttemptId" -> fields.put(mutation, "not-a-uuid");
        case "claimId", "accountAttemptId", "executionId" ->
            fields.put(mutation, UUID.randomUUID().toString());
        case "requestDigest", "resultDigest" -> fields.put(mutation, "f".repeat(64));
        default -> fields.put(mutation, "foreign");
      }
    }
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(canonical(JSON.valueToTree(fields)).getBytes(StandardCharsets.UTF_8));
    return JSON.writeValueAsString(
        Map.of(
            "tenant",
            value,
            "receipt",
            encoded + "." + mac("tenant-held-bootstrap-receipt/v1\n" + encoded)));
  }

  private String mac(String value) throws Exception {
    Mac hmac = Mac.getInstance("HmacSHA256");
    hmac.init(new SecretKeySpec(key, "HmacSHA256"));
    return HexFormat.of().formatHex(hmac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static String canonical(JsonNode value) throws Exception {
    if (value.isObject()) {
      var fields = new TreeMap<String, JsonNode>();
      value
          .fields()
          .forEachRemaining(
              e -> {
                if (!e.getValue().isNull()) fields.put(e.getKey(), e.getValue());
              });
      var output = new StringBuilder("{");
      for (var entry : fields.entrySet()) {
        if (output.length() > 1) output.append(',');
        output
            .append(JSON.writeValueAsString(entry.getKey()))
            .append(':')
            .append(canonical(entry.getValue()));
      }
      return output.append('}').toString();
    }
    if (value.isArray()) {
      var output = new StringBuilder("[");
      for (var entry : value) {
        if (output.length() > 1) output.append(',');
        output.append(canonical(entry));
      }
      return output.append(']').toString();
    }
    if (value.isNumber()) return value.decimalValue().stripTrailingZeros().toPlainString();
    return JSON.writeValueAsString(value);
  }
}
