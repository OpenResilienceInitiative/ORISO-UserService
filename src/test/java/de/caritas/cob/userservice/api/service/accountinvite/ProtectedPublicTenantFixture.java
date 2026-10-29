package de.caritas.cob.userservice.api.service.accountinvite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantCreationClient;
import de.caritas.cob.userservice.api.service.accountpolicy.TaskContextProof;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/** Explicit remote Tenant port double; the User transport/receipt and native broker remain real. */
@Import(ProtectedPublicTenantFixture.NativeImport.class)
class ProtectedPublicTenantFixture {
  static final AtomicBoolean CREATED = new AtomicBoolean();
  static final String CAPABILITY = "synthetic-held-public-tenant-17";
  static MockRestServiceServer boundary;

  static class NativeImport implements ImportSelector {
    public String[] selectImports(AnnotationMetadata metadata) {
      return new String[] {
        "de.caritas.cob.userservice.api.service.accountinvite.onboarding.ReservedTenantBootstrap"
      };
    }
  }

  @Bean
  TenantCreationClient pairActualTenant(
      SecurityHeaderSupplier headers,
      IdentityAuthentication authentication,
      IdentityClientConfig identity,
      TenantAdminServiceApiControllerFactory factory) {
    return spy(new TenantCreationClient(headers, authentication, identity, factory));
  }

  @Bean("restTemplate")
  RestTemplate pairHeldTenantPort(
      JwtDecoder decoder, AccountLifecycleCodec json, JdbcTemplate jdbc) {
    var http = new RestTemplate();
    boundary = MockRestServiceServer.bindTo(http).build();
    CREATED.set(false);
    if ("reserved-new".equals(ProtectedAdminPublicNativePairProof.required("PAIR_SCENARIO"))) {
      boundary
          .expect(
              org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                  "http://held-tenant.fixture.invalid/tenantadmin/bootstrap"))
          .andExpect(
              org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                  org.springframework.http.HttpMethod.POST))
          .andRespond(
              request -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
                String header = request.getHeaders().getFirst("Authorization");
                assertNotNull(header);
                assertTrue(header.startsWith("Bearer "));
                var actual = decoder.decode(header.substring(7));
                assertEquals(
                    ProtectedAdminPublicNativePairProof.required("PAIR_SETUP_SUBJECT"),
                    actual.getSubject());
                assertEquals("backend-setup", actual.getClaimAsString("azp"));
                assertEquals("0", request.getHeaders().getFirst("tenantId"));
                assertEquals(
                    Set.of(
                        "tenantservice",
                        "agencyservice",
                        "consultingtypeservice",
                        "userservice",
                        "oriso-task-commands"),
                    Set.copyOf(actual.getAudience()));
                JsonNode command =
                    json.read(((MockClientHttpRequest) request).getBodyAsString(), JsonNode.class);
                var tenant = command.path("tenant");
                assertEquals(17, tenant.path("id").asLong());
                assertEquals(CAPABILITY, tenant.path("tenantIdReservationToken").asText());
                assertEquals(
                    CAPABILITY,
                    jdbc.queryForObject(
                        "SELECT tenant_id_reservation_token FROM account_invite", String.class));
                assertTrue(tenant.path("onboardingDpaAcceptance").path("accepted").asBoolean());
                assertEquals(
                    "displayed-dpa-v1",
                    tenant.path("onboardingDpaAcceptance").path("dpaVersion").asText());
                assertEquals(
                    1,
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM protected_account_creation WHERE state='CREATED'",
                        Integer.class));
                assertEquals(
                    0,
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM protected_account_activation", Integer.class));
                for (String field : List.of("claimId", "accountAttemptId", "executionId"))
                  assertEquals(
                      command.path(field).asText(),
                      UUID.fromString(command.path(field).asText()).toString());
                var result = ((com.fasterxml.jackson.databind.node.ObjectNode) tenant).deepCopy();
                result.remove("tenantIdReservationToken");
                var receipt = new TreeMap<String, Object>();
                receipt.put("v", 1);
                receipt.put("ownerIssuer", actual.getIssuer().toString());
                receipt.put("ownerClient", actual.getClaimAsString("azp"));
                receipt.put("ownerSubject", actual.getSubject());
                for (String field : List.of("claimId", "accountAttemptId", "executionId"))
                  receipt.put(field, command.path(field).asText());
                receipt.put("bootstrapAttemptId", UUID.randomUUID().toString());
                receipt.put("tenantId", 17);
                receipt.put(
                    "requestDigest", tenantDigest("tenant-held-bootstrap-request/v1", command));
                receipt.put(
                    "resultDigest", tenantDigest("tenant-held-bootstrap-result/v1", result));
                receipt.put("state", "COMPLETE");
                String payload =
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(json.canonical(receipt).getBytes(StandardCharsets.UTF_8));
                try {
                  var mac = Mac.getInstance("HmacSHA256");
                  mac.init(
                      new SecretKeySpec(
                          Base64.getDecoder()
                              .decode(
                                  ProtectedAdminPublicNativePairProof.required(
                                      "PAIR_CONTEXT_KEY_B64")),
                          "HmacSHA256"));
                  String proof =
                      payload
                          + "."
                          + HexFormat.of()
                              .formatHex(
                                  mac.doFinal(
                                      ("tenant-held-bootstrap-receipt/v1\n" + payload)
                                          .getBytes(StandardCharsets.UTF_8)));
                  assertTrue(CREATED.compareAndSet(false, true));
                  return MockRestResponseCreators.withSuccess(
                          json.canonical(Map.of("tenant", result, "receipt", proof)),
                          MediaType.APPLICATION_JSON)
                      .createResponse(request);
                } catch (java.security.GeneralSecurityException unavailable) {
                  throw new IllegalStateException("Synthetic Tenant receipt unavailable");
                }
              });
    }
    return http;
  }

  // The receiving Tenant contract omits null object fields; lifecycle JSON preserves them.
  private static String tenantDigest(String domain, JsonNode value) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              Base64.getDecoder()
                  .decode(ProtectedAdminPublicNativePairProof.required("PAIR_CONTEXT_KEY_B64")),
              "HmacSHA256"));
      mac.update((domain + "\n").getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(mac.doFinal(TaskContextProof.canonical(value)));
    } catch (Exception unavailable) {
      throw new IllegalStateException("Synthetic Tenant digest unavailable");
    }
  }
}
