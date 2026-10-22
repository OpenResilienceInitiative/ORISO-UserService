package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.service.accountpolicy.TaskContextProof;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTenantDTO;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Fixed protected held-bootstrap transport; no ordinary creation route is changed. */
@Component
@ConditionalOnProperty(name = "TASK_IDENTITY_MODEL", havingValue = "FIVE_ACTORS")
final class ReservedTenantBootstrap {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final RestTemplate http;
  private final SecurityHeaderSupplier headers;
  private final TaskActorTokenBroker broker;
  private final JwtDecoder decoder;
  private final Environment environment;
  private final String url;

  ReservedTenantBootstrap(
      @Qualifier("restTemplate") RestTemplate http,
      SecurityHeaderSupplier headers,
      TaskActorTokenBroker broker,
      JwtDecoder decoder,
      Environment environment,
      @org.springframework.beans.factory.annotation.Value("${tenant.service.api.url}")
          String base) {
    this.http = http;
    this.headers = headers;
    this.broker = broker;
    this.decoder = decoder;
    this.environment = environment;
    URI address = URI.create(base);
    if (!Set.of("http", "https").contains(address.getScheme())
        || address.getHost() == null
        || address.getRawUserInfo() != null
        || address.getRawQuery() != null
        || address.getRawFragment() != null)
      throw new IllegalArgumentException("Fixed Tenant service URL required");
    this.url = base.replaceAll("/+$", "") + "/tenantadmin/bootstrap";
  }

  Reply create(AcceptanceClaim claim, MultilingualTenantDTO tenant) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || !TransactionSynchronizationManager.getResourceMap().isEmpty())
      throw new IllegalStateException("Held Tenant HTTP requires committed local ownership");
    if (claim == null
        || claim.frozen() == null
        || claim.claimId() == null
        || claim.attemptId() == null
        || claim.executionId() == null
        || claim.journalVersion() != 1
        || tenant == null
        || tenant.getId() == null
        || tenant.getId() < 1
        || tenant.getTenantIdReservationToken() == null
        || tenant.getTenantIdReservationToken().isBlank()
        || tenant.getTenantIdReservationToken().length() > 4096)
      throw new IllegalArgumentException("Exact held Tenant claim and reservation required");
    if (!TaskContextProof.grouped(environment))
      throw new IllegalStateException("Held Tenant transport unavailable");
    try {
      byte[] key = TaskContextProof.key(environment);
      JsonNode scope = JSON.readTree(claim.frozen().requestJson()).path("scope");
      JsonNode targets = scope.path("targetTenantIds"),
          reservation = scope.path("reservationBinding");
      long id = tenant.getId();
      if (!"TENANT_ADMIN".equals(scope.path("protectedKind").textValue())
          || !"RESERVED_NEW".equals(scope.path("tenantMode").textValue())
          || !targets.isArray()
          || targets.size() != 1
          || !targets.get(0).isIntegralNumber()
          || !targets.get(0).canConvertToLong()
          || targets.get(0).longValue() != id
          || !reservation.path("tenantId").isIntegralNumber()
          || !reservation.path("tenantId").canConvertToLong()
          || reservation.path("tenantId").longValue() != id
          || !scope
              .path("workflowId")
              .asText()
              .equals(reservation.path("invitationWorkflowId").textValue())
          || !same(
              reservation.path("capabilityDigest").textValue(),
              digest(
                  key,
                  "protected-reservation-capability/v1",
                  Map.of(
                      "workflowId",
                      scope.path("workflowId").asText(),
                      "tenantId",
                      id,
                      "capability",
                      tenant.getTenantIdReservationToken())))) throw denied();
      var token = broker.issue(TaskPurpose.CONFIG_WIZARD);
      if (token == null || token.actor() != TaskActor.SETUP) throw denied();
      var caller = decoder.decode(token.accessToken());
      if (caller.getIssuer() == null
          || caller.getExpiresAt() == null
          || !caller.getExpiresAt().isAfter(Instant.now())
          || !scope.path("setupClient").asText().equals(caller.getClaimAsString("azp"))
          || !scope.path("setupSubject").asText().equals(caller.getSubject())) throw denied();
      var command =
          JSON.createObjectNode()
              .put("claimId", claim.claimId().toString())
              .put("accountAttemptId", claim.attemptId().toString())
              .put("executionId", claim.executionId().toString());
      command.set("tenant", JSON.valueToTree(tenant));
      byte[] body = JSON.writeValueAsBytes(command);
      if (body.length > 1_048_576) throw denied();
      String requestDigest = digest(key, "tenant-held-bootstrap-request/v1", command);
      var outgoing = headers.getKeycloakAndCsrfHttpHeaders(token.accessToken());
      outgoing.set("tenantId", "0");
      var response =
          http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, outgoing), byte[].class);
      byte[] raw = response.getBody();
      if (response.getStatusCode().value() != 200 || raw == null || raw.length > 1_048_576)
        throw invalid();
      JsonNode result = JSON.readTree(raw);
      fields(result, Set.of("tenant", "receipt"));
      String receipt = result.path("receipt").textValue();
      if (receipt == null || receipt.length() > 16384) throw invalid();
      String[] parts = receipt.split("\\.", -1);
      if (parts.length != 2
          || !parts[0].matches("[A-Za-z0-9_-]+")
          || !parts[1].matches("[a-f0-9]{64}")) throw invalid();
      byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(payload).equals(parts[0])
          || !MessageDigest.isEqual(
              HexFormat.of().parseHex(parts[1]),
              mac(key, "tenant-held-bootstrap-receipt/v1\n" + parts[0]))) throw invalid();
      JsonNode verified = JSON.readTree(payload);
      fields(
          verified,
          Set.of(
              "v",
              "ownerIssuer",
              "ownerClient",
              "ownerSubject",
              "claimId",
              "accountAttemptId",
              "executionId",
              "bootstrapAttemptId",
              "tenantId",
              "requestDigest",
              "resultDigest",
              "state"));
      if (!MessageDigest.isEqual(payload, TaskContextProof.canonical(verified))
          || !verified.path("v").isIntegralNumber()
          || !verified.path("v").canConvertToInt()
          || verified.path("v").intValue() != 1
          || !verified.path("tenantId").isIntegralNumber()
          || !verified.path("tenantId").canConvertToLong()
          || verified.path("tenantId").longValue() != id
          || !"COMPLETE".equals(verified.path("state").textValue())
          || !caller.getIssuer().toString().equals(verified.path("ownerIssuer").textValue())
          || !caller.getClaimAsString("azp").equals(verified.path("ownerClient").textValue())
          || !caller.getSubject().equals(verified.path("ownerSubject").textValue())
          || !claim.claimId().toString().equals(verified.path("claimId").textValue())
          || !claim.attemptId().toString().equals(verified.path("accountAttemptId").textValue())
          || !claim.executionId().toString().equals(verified.path("executionId").textValue())
          || !same(requestDigest, verified.path("requestDigest").textValue())) throw invalid();
      String marker = verified.path("bootstrapAttemptId").textValue();
      if (marker == null || !UUID.fromString(marker).toString().equals(marker)) throw invalid();
      JsonNode actualTenant = result.path("tenant");
      if (!actualTenant.isObject()
          || !actualTenant.path("id").isIntegralNumber()
          || !actualTenant.path("id").canConvertToLong()
          || actualTenant.path("id").longValue() != id
          || !same(
              digest(key, "tenant-held-bootstrap-result/v1", actualTenant),
              verified.path("resultDigest").textValue())) throw invalid();
      var value = JSON.treeToValue(actualTenant, MultilingualTenantDTO.class);
      return new Reply(id, receipt, value, claim.claimId(), claim.attemptId(), claim.executionId());
    } catch (ResponseStatusException | AccessDeniedException refusal) {
      throw refusal;
    } catch (HttpStatusCodeException failure) {
      int code = failure.getStatusCode().value();
      if (code == 400 || code == 403 || code == 409)
        throw new ResponseStatusException(failure.getStatusCode(), "Held Tenant request refused");
      throw pending();
    } catch (RestClientException unknown) {
      throw pending();
    } catch (Exception malformed) {
      throw invalid();
    }
  }

  static MultilingualTenantDTO copy(MultilingualTenantDTO value) {
    try {
      return JSON.readValue(JSON.writeValueAsBytes(value), MultilingualTenantDTO.class);
    } catch (Exception unavailable) {
      throw invalid();
    }
  }

  private static String digest(byte[] key, String domain, Object value) throws Exception {
    return HexFormat.of()
        .formatHex(
            mac(
                key,
                domain
                    + "\n"
                    + new String(TaskContextProof.canonical(value), StandardCharsets.UTF_8)));
  }

  private static byte[] mac(byte[] key, String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean same(String left, String right) {
    return left != null
        && right != null
        && left.matches("[a-f0-9]{64}")
        && right.matches("[a-f0-9]{64}")
        && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }

  private static void fields(JsonNode value, Set<String> expected) {
    if (value == null || !value.isObject()) throw invalid();
    var names = new java.util.HashSet<String>();
    value.fieldNames().forEachRemaining(names::add);
    if (!names.equals(expected)) throw invalid();
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("Exact protected Tenant scope required");
  }

  private static ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Held Tenant response unavailable");
  }

  private static ResponseStatusException pending() {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "Held Tenant outcome is pending");
  }

  record Reply(
      long id,
      String receipt,
      MultilingualTenantDTO tenant,
      UUID claim,
      UUID attempt,
      UUID execution) {
    @Override
    public String toString() {
      return "HeldTenantReply[redacted]";
    }
  }
}
