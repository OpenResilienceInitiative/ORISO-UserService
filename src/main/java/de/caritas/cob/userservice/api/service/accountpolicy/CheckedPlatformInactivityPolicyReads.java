package de.caritas.cob.userservice.api.service.accountpolicy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/** The finite prelogin platform-default projection; ordinary tenant reads stay unchanged. */
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="TASK_IDENTITY_MODEL", havingValue="FIVE_ACTORS")
public final class CheckedPlatformInactivityPolicyReads {
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final RestTemplate http;
  private final TaskActorTokenBroker broker;
  private final JwtDecoder decoder;
  private final Environment environment;
  private final Clock clock;
  private final String url;

  public CheckedPlatformInactivityPolicyReads(@org.springframework.beans.factory.annotation.Qualifier("restTemplate") RestTemplate http,
      TaskActorTokenBroker broker, JwtDecoder decoder, Environment environment, Clock clock,
      @org.springframework.beans.factory.annotation.Value("${tenant.service.api.url}") String base) {
    this.http = http; this.broker = broker; this.decoder = decoder;
    this.environment = environment; this.clock = clock;
    URI address = URI.create(base);
    if (!Set.of("http", "https").contains(address.getScheme()) || address.getHost() == null
        || address.getRawUserInfo() != null || address.getRawQuery() != null || address.getRawFragment() != null)
      throw unavailable();
    this.url = base.replaceAll("/+$", "") + "/internal/platform/account-inactivity-policy";
  }

  public Policy capture() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable();
    try {
      if (!TaskContextProof.grouped(environment)) throw unavailable();
      byte[] key = TaskContextProof.key(environment);
      var grant = broker.issue(TaskPurpose.RUNTIME_POLICY);
      if (grant == null || grant.actor() != TaskActor.RUNTIME) throw unavailable();
      var caller = decoder.decode(grant.accessToken());
      long now = clock.instant().getEpochSecond();
      if (caller.getIssuer() == null || caller.getExpiresAt() == null
          || caller.getExpiresAt().getEpochSecond() <= now || caller.getSubject() == null
          || caller.getClaimAsString("azp") == null) throw unavailable();
      long expires = Math.min(now + 30, caller.getExpiresAt().getEpochSecond());
      Map<String, Object> claims = new TreeMap<>();
      claims.put("v", 2); claims.put("iss", "userservice"); claims.put("aud", "tenantservice");
      claims.put("actor", "RUNTIME"); claims.put("purpose", "RUNTIME_POLICY");
      claims.put("operation", "platform.account-inactivity.read"); claims.put("method", "GET");
      claims.put("target", "platform/0/account-inactivity"); claims.put("tenantId", 0);
      claims.put("sub", caller.getSubject()); claims.put("azp", caller.getClaimAsString("azp"));
      claims.put("tokenIssuer", caller.getIssuer().toString());
      claims.put("iat", now); claims.put("exp", expires); claims.put("nonce", UUID.randomUUID().toString());
      var headers = new HttpHeaders(); headers.setBearerAuth(grant.accessToken()); headers.set("tenantId", "0");
      headers.set("X-ORISO-Platform-Inactivity-Context", TaskContextProof.sign(key, claims));
      var response = http.exchange(url, HttpMethod.GET, new HttpEntity<Void>(headers), byte[].class);
      byte[] raw = response.getBody();
      if (response.getStatusCode().value() != 200 || raw == null || raw.length > 4096) throw unavailable();
      JsonNode value = JSON.readTree(raw);
      if (value == null || !value.isObject() || value.size() != 2 || !value.has("otherMonths") || !value.has("revision")) throw unavailable();
      JsonNode months = value.path("otherMonths"), revision = value.path("revision");
      if (!months.isIntegralNumber() || !months.canConvertToInt() || months.intValue() < 1
          || !revision.isIntegralNumber() || !revision.canConvertToLong() || revision.longValue() < 0) throw unavailable();
      return new Policy(months.intValue(), revision.longValue(), clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    } catch (Exception unknown) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() { return new IllegalStateException("Checked platform inactivity policy unavailable"); }
}
