package de.caritas.cob.userservice.api.service.accountpolicy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

class CheckedPlatformInactivityPolicyReadsTest {
  private final RestTemplate http = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.createServer(http);
  private final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
  private final JwtDecoder decoder = mock(JwtDecoder.class);
  private final Instant now = Instant.parse("2026-10-22T12:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private final CheckedPlatformInactivityPolicyReads client =
      new CheckedPlatformInactivityPolicyReads(
          http,
          broker,
          decoder,
          new MockEnvironment()
              .withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS")
              .withProperty(
                  "ORISO_TASK_CONTEXT_KEY", Base64.getEncoder().encodeToString(new byte[32])),
          clock,
          "https://tenant.example");

  @BeforeEach
  void setup() {
    when(broker.issue(TaskPurpose.RUNTIME_POLICY))
        .thenReturn(new TaskActorTokenBroker.TaskToken(TaskActor.RUNTIME, "synthetic-runtime", 60));
    when(decoder.decode("synthetic-runtime"))
        .thenReturn(
            Jwt.withTokenValue("synthetic-runtime")
                .header("alg", "RS256")
                .issuer("https://identity.example/realms/test")
                .subject("runtime-subject")
                .claim("azp", "backend-runtime")
            .audience(java.util.List.of("tenantservice", "agencyservice", "appointmentservice"))
            .claim("realm_access", java.util.Map.of("roles", java.util.List.of("runtime-policy", "matrix-agency", "matrix-agency-provision", "appointment-sync", "appointment-participant-cleanup")))
                .issuedAt(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(60))
                .build());
  }

  @AfterEach
  void cleanup() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void fixedRuntimeReadAuthenticatesTheExactOperationAndUsesOnlyAuthoritativeValues()
      throws Exception {
    server
        .expect(requestTo("https://tenant.example/internal/platform/account-inactivity-policy"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer synthetic-runtime"))
        .andExpect(
            request -> {
              String[] parts =
                  request.getHeaders().getFirst("X-ORISO-Platform-Inactivity-Context").split("\\.");
              assertThat(Base64.getUrlDecoder().decode(parts[1]))
                  .isEqualTo(mac("proof\n" + parts[0]));
              var claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[0]));
              assertThat(claims.size()).isEqualTo(15);
              assertThat(claims.path("operation").asText())
                  .isEqualTo("platform.account-inactivity.read");
              assertThat(claims.path("target").asText()).isEqualTo("platform/0/account-inactivity");
              assertThat(claims.path("actor").asText()).isEqualTo("RUNTIME");
              assertThat(claims.path("purpose").asText()).isEqualTo("RUNTIME_POLICY");
              assertThat(claims.path("sub").asText()).isEqualTo("runtime-subject");
              assertThat(claims.path("azp").asText()).isEqualTo("backend-runtime");
              assertThat(claims.path("tokenIssuer").asText())
                  .isEqualTo("https://identity.example/realms/test");
              assertThat(claims.path("iat").longValue()).isEqualTo(now.getEpochSecond());
              assertThat(claims.path("exp").longValue())
                  .isEqualTo(now.plusSeconds(30).getEpochSecond());
            })
        .andRespond(withSuccess("{\"otherMonths\":18,\"revision\":7}", MediaType.APPLICATION_JSON));
    var result = client.capture();
    assertThat(result).isNotNull();
    assertThat(result.months()).isEqualTo(18);
    assertThat(result.revision()).isEqualTo(7);
    assertThat(result.capturedAt()).isEqualTo(now);
    server.verify();
  }

  @Test
  void malformedPartialExtraAndUnavailablePoliciesNeverBecomeDefaults() {
    for (String response :
        new String[] {
          "{}",
          "{\"otherMonths\":null,\"revision\":1}",
          "{\"otherMonths\":0,\"revision\":1}",
          "{\"otherMonths\":18.5,\"revision\":1}",
          "{\"otherMonths\":\"18\",\"revision\":1}",
          "{\"otherMonths\":18,\"revision\":-1}",
          "{\"otherMonths\":18,\"revision\":1,\"extra\":false}",
          "{\"otherMonths\":18,\"otherMonths\":19,\"revision\":1}"
        }) {
      server.reset();
      server.expect(anything()).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
      assertThatThrownBy(client::capture).isInstanceOf(IllegalStateException.class);
      server.verify();
    }
  }

  @lombok.SneakyThrows
  private static byte[] mac(String value) {
    Mac hmac = Mac.getInstance("HmacSHA256");
    hmac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
    return hmac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  void ambientSqlRefusesBeforeAnyTokenOrRequest() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(client::capture).isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.clear();
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(client::capture).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(broker, decoder);
    server.verify();
  }
}
