package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityCredentials;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Issues the frozen, payload-bound command proof without persisting passwords or human tokens. */
@Component
public final class IdentityOriginProof {
  private final byte[] provisioningKey;
  private final byte[] maintenanceKey;
  private final Clock clock;
  private final ObjectMapper json = new ObjectMapper();

  @Autowired
  public IdentityOriginProof(
      @Value("${oriso.commands.provisioning-origin-key:}") String provisioning,
      @Value("${oriso.commands.maintenance-origin-key:}") String maintenance) {
    this(provisioning, maintenance, Clock.systemUTC());
  }

  public IdentityOriginProof(String provisioning, String maintenance, Clock clock) {
    this.provisioningKey = decodeKey(provisioning);
    this.maintenanceKey = decodeKey(maintenance);
    if (java.security.MessageDigest.isEqual(provisioningKey, maintenanceKey)) {
      throw new IllegalStateException("Provisioning and maintenance origin keys must be distinct");
    }
    this.clock = clock;
  }

  public String issue(
      TaskIdentityCredentials identity,
      IdentityCommandAuthorization authorization,
      Map<String, ?> command) {
    if (identity.getTask()
            != de.caritas.cob.userservice.api.config.auth.TaskIdentity.ACCOUNT_PROVISIONING
        && identity.getTask()
            != de.caritas.cob.userservice.api.config.auth.TaskIdentity.ACCOUNT_MAINTENANCE)
      throw new org.springframework.security.access.AccessDeniedException(
          "Command signing requires a bound account task identity");
    byte[] key =
        identity.getTask()
                == de.caritas.cob.userservice.api.config.auth.TaskIdentity.ACCOUNT_PROVISIONING
            ? provisioningKey
            : maintenanceKey;
    try {
      String payload = canonicalJson(command);
      String digest =
          Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, "payload\n" + payload));
      var now = clock.instant();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer("oriso-userservice")
              .audience("oriso-task-commands")
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(60)))
              .jwtID(UUID.randomUUID().toString())
              .claim("purpose", "oriso-command")
              .claim("operation", authorization.operation())
              .claim("taskClient", identity.getClientId())
              .claim("taskSubject", identity.getServiceSubject())
              .claim("originKind", authorization.originKind())
              .claim("originAction", authorization.operation())
              .claim("target", authorization.target())
              .claim("tenantId", authorization.tenantId())
              .claim("roles", authorization.roles())
              .claim("payloadDigest", digest)
              .build();
      var jwt =
          new JWSObject(
              new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
              new Payload(claims.toJSONObject(true)));
      jwt.sign(new MACSigner(key));
      return jwt.serialize();
    } catch (Exception exception) {
      throw new IllegalStateException("Could not issue identity command authorization");
    }
  }

  public String canonicalJson(Map<String, ?> command) {
    try {
      return json.writeValueAsString(canonical(command));
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalArgumentException("Unsupported identity command payload");
    }
  }

  private static Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach(
          (key, item) -> {
            if (!(key instanceof String text))
              throw new IllegalArgumentException("Command keys must be strings");
            sorted.put(text, canonical(item));
          });
      return sorted;
    }
    if (value instanceof Collection<?> items)
      return items.stream().map(IdentityOriginProof::canonical).toList();
    if (value == null
        || value instanceof String
        || value instanceof Boolean
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte) return value;
    throw new IllegalArgumentException("Unsupported identity command value");
  }

  private static byte[] hmac(byte[] key, String value) throws GeneralSecurityException {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] decodeKey(String value) {
    try {
      byte[] key = Base64.getDecoder().decode(value);
      if (key.length < 32) throw new IllegalArgumentException();
      return key;
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Configure distinct managed origin authorization keys of at least 256 bits");
    }
  }
}
