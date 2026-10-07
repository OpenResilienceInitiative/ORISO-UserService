package de.caritas.cob.userservice.api.workflow.accountinactivity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.MACVerifier;
import com.sun.net.httpserver.HttpExchange;
import de.caritas.cob.userservice.api.adapters.keycloak.commands.*;
import de.caritas.cob.userservice.api.config.auth.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

/** Actual signed command transport shared by the inactivity HTTP regression fixtures. */
public final class InactivityCommandTestSupport {
  private static final byte[] KEY =
      "different-test-only-origin-key-32".getBytes(StandardCharsets.UTF_8);

  private InactivityCommandTestSupport() {}

  public static KeycloakInactivityLifecycle lifecycle(JdbcTemplate jdbc, String host) {
    var identities = new TaskIdentityConfiguration();
    identities
        .getTasks()
        .put(
            "account-maintenance",
            new TaskIdentityCredentials(
                "backend-account-maintenance", "test-maintenance-secret", "maintenance-subject"));
    var grants = org.mockito.Mockito.mock(TaskIdentityGrant.class);
    org.mockito.Mockito.when(grants.token(TaskIdentity.ACCOUNT_MAINTENANCE))
        .thenReturn("bounded-maintenance-token");
    var proof =
        new IdentityOriginProof(
            Base64.getEncoder().encodeToString(new byte[32]),
            Base64.getEncoder().encodeToString(KEY),
            Clock.systemUTC());
    return new KeycloakInactivityLifecycle(
        jdbc,
        new KeycloakTaskCommands(
            new RestTemplate(new JdkClientHttpRequestFactory()),
            identities,
            grants,
            proof,
            host,
            "test"));
  }

  public static void verify(
      HttpExchange exchange, String body, String id, Long tenant, String operation)
      throws Exception {
    assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer bounded-maintenance-token");
    var proof =
        JWSObject.parse(exchange.getRequestHeaders().getFirst("X-ORISO-Origin-Authorization"));
    assertThat(proof.verify(new MACVerifier(KEY))).isTrue();
    var claims = proof.getPayload().toJSONObject();
    assertThat(claims)
        .containsEntry("operation", operation)
        .containsEntry("originKind", "LIFECYCLE")
        .containsEntry("target", id)
        .containsEntry("tenantId", tenant == null ? null : tenant.toString())
        .containsEntry("roles", List.of())
        .containsEntry("taskClient", "backend-account-maintenance")
        .containsEntry("taskSubject", "maintenance-subject");
    String canonical = body.isBlank() ? "{}" : body;
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
    assertThat(claims.get("payloadDigest"))
        .isEqualTo(
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    mac.doFinal(("payload\n" + canonical).getBytes(StandardCharsets.UTF_8))));
    if (operation.equals("account.lifecycle-status"))
      assertThat(exchange.getRequestMethod()).isEqualTo("GET");
    else assertThat(exchange.getRequestMethod()).isEqualTo("POST");
    var request = new ObjectMapper().readTree(canonical);
    if (operation.equals("account.restore")) assertThat(request.size()).isEqualTo(1);
    else assertThat(request.size()).isZero();
  }
}
