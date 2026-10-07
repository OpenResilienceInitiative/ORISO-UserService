package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.config.auth.TaskIdentity;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.*;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Invitation-derived license limit projection; no general tenant settings or ambient task
 * authority.
 */
@Component
public final class WizardAccountPolicyClient {
  public record Policy(Long id, Integer allowedNumberOfUsers) {}

  private final RestTemplate http;
  private final TaskIdentityGrant grants;
  private final String base;
  private final byte[] key;
  private final Clock clock;

  @Autowired
  public WizardAccountPolicyClient(
      @Qualifier("restTemplate") RestTemplate http,
      TaskIdentityGrant grants,
      @Value("${tenant.service.api.url}") String host,
      @Value("${oriso.commands.wizard-policy-context-key:}") String encodedKey) {
    this(http, grants, host, encodedKey, Clock.systemUTC());
  }

  public WizardAccountPolicyClient(
      RestTemplate http, TaskIdentityGrant grants, String host, String encodedKey, Clock clock) {
    this.http = http;
    this.grants = grants;
    this.base = host.replaceAll("/+$", "");
    this.clock = clock;
    try {
      this.key = Base64.getDecoder().decode(encodedKey);
      if (key.length < 32) throw new IllegalArgumentException();
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException(
          "Configure a managed Wizard policy authorization key of at least 256 bits");
    }
  }

  public Policy read(IdentityCreationOrigin origin) {
    if (!Set.of("INVITATION", "IMPORT").contains(origin.originKind())
        || origin.tenantId() == null
        || origin.tenantId() <= 0
        || !Set.of("CONSULTANT", "CONSULTANT_AGENCY_ADMIN").contains(origin.registrationKind()))
      throw new AccessDeniedException(
          "Wizard policy read requires verified counselling account creation");
    var grant = grants.verified(TaskIdentity.CONFIG_WIZARD);
    var jwt = grant.claims();
    if (jwt == null || jwt.getIssuer() == null)
      throw new AccessDeniedException("Wizard policy read has no verified token issuer");
    long now = clock.instant().getEpochSecond();
    var claims = new TreeMap<String, Object>();
    claims.put("aud", "tenantservice");
    claims.put("azp", jwt.getClaimAsString("azp"));
    claims.put("exp", now + 60);
    claims.put("iat", now);
    claims.put("iss", "oriso-userservice");
    claims.put("nonce", UUID.randomUUID().toString());
    claims.put("operation", "wizard.account-policy.read");
    claims.put("sub", jwt.getSubject());
    claims.put("tenantId", origin.tenantId());
    claims.put("tokenIssuer", jwt.getIssuer().toString());
    claims.put("v", 1);
    var headers = new HttpHeaders();
    headers.setBearerAuth(grant.token());
    headers.set("X-ORISO-Wizard-Policy-Context", sign(claims));
    var url =
        UriComponentsBuilder.fromUriString(base)
            .pathSegment(
                "internal", "tenants", origin.tenantId().toString(), "account-provisioning-policy")
            .build()
            .toUriString();
    var policy =
        http.exchange(url, HttpMethod.GET, new HttpEntity<Void>(headers), Policy.class).getBody();
    if (policy == null || !Objects.equals(policy.id(), origin.tenantId()))
      throw new IllegalStateException(
          "Wizard account policy projection has a foreign or missing tenant");
    return policy;
  }

  private String sign(Map<String, Object> claims) {
    try {
      var b64 = Base64.getUrlEncoder().withoutPadding();
      String payload = b64.encodeToString(new ObjectMapper().writeValueAsBytes(claims));
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return payload
          + "."
          + b64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
    } catch (Exception error) {
      throw new IllegalStateException("Could not authorize Wizard policy read");
    }
  }
}
