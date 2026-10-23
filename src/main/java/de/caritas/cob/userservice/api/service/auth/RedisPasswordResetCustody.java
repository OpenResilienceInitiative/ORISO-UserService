package de.caritas.cob.userservice.api.service.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.service.auth.OneTimeTokenStore.PasswordResetClaim;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Atomic original issuance custody. Data alone never authorizes a password provider operation. */
final class RedisPasswordResetCustody {
  private static final Duration RECOVERY_RETENTION = Duration.ofDays(7);
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final DefaultRedisScript<Long> STORE =
      new DefaultRedisScript<>(
          """
      if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
      local priorKey = redis.call('GET', KEYS[3])
      if priorKey then
        local prior = redis.call('GET', priorKey)
        if prior then
          local state = cjson.decode(prior)
          if state.status == 'ISSUED' or state.status == 'REJECTED' then
            redis.call('DEL', state.locator, priorKey)
          end
        end
      end
      redis.call('SET', KEYS[1], ARGV[1], 'PXAT', ARGV[2])
      redis.call('SET', KEYS[2], KEYS[1], 'PXAT', ARGV[2])
      redis.call('SET', KEYS[3], KEYS[1], 'PXAT', ARGV[2])
      return 1
      """,
          Long.class);
  private static final DefaultRedisScript<Long> DELIVERY =
      new DefaultRedisScript<>(
          """
      local raw = redis.call('GET', KEYS[1])
      if not raw then return 0 end
      local state = cjson.decode(raw)
      if state.binding ~= ARGV[1] then return 0 end
      if state.delivery == ARGV[2] then return 1 end
      if not ((state.delivery == 'PENDING' and ARGV[2] == 'ATTEMPTED') or
          (state.delivery == 'ATTEMPTED' and (ARGV[2] == 'SENT' or ARGV[2] == 'UNKNOWN'))) then return 0 end
      state.delivery = ARGV[2]
      redis.call('SET', KEYS[1], cjson.encode(state), 'KEEPTTL')
      return 1
      """,
          Long.class);
  private static final DefaultRedisScript<String> CLAIM =
      new DefaultRedisScript<>(
          """
      local flow = redis.call('GET', KEYS[1])
      if not flow then return nil end
      local raw = redis.call('GET', flow)
      if not raw then return nil end
      local state = cjson.decode(raw)
      if state.locator ~= KEYS[1] then return nil end
      if state.status == 'FROZEN' or state.status == 'ACKNOWLEDGED' or state.status == 'REFUSED' or
          (state.status == 'REJECTED' and state.requestDigest == ARGV[1]) then
        if state.requestDigest == ARGV[1] then return raw end
        return nil
      end
      if state.status ~= 'ISSUED' and state.status ~= 'REJECTED' then return nil end
      local time = redis.call('TIME')
      local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
      if state.expires <= now or redis.call('GET', state.subjectIndex) ~= flow then return nil end
      if state.delivery ~= 'ATTEMPTED' and state.delivery ~= 'SENT' and state.delivery ~= 'UNKNOWN' then return nil end
      if state.status == 'REJECTED' then state.version = state.version + 1 end
      state.status = 'FROZEN'
      state.requestDigest = ARGV[1]
      local changed = cjson.encode(state)
      redis.call('SET', flow, changed, 'PXAT', state.recoverUntil)
      redis.call('PEXPIREAT', KEYS[1], state.recoverUntil)
      return changed
      """,
          String.class);
  private static final DefaultRedisScript<Long> FINISH =
      new DefaultRedisScript<>(
          """
      local raw = redis.call('GET', KEYS[1])
      if not raw then return 0 end
      local state = cjson.decode(raw)
      if state.binding ~= ARGV[1] or state.claimId ~= ARGV[2] or
          state.version ~= tonumber(ARGV[3]) or state.requestDigest ~= ARGV[4] then return 0 end
      if state.status == ARGV[5] then return 1 end
      if state.status ~= 'FROZEN' then return 0 end
      state.status = ARGV[5]
      redis.call('SET', KEYS[1], cjson.encode(state), 'KEEPTTL')
      return 1
      """,
          Long.class);
  private static final DefaultRedisScript<Long> REQUIRE_FRESH =
      new DefaultRedisScript<>(
          """
      local raw = redis.call('GET', KEYS[1])
      if not raw then return 0 end
      local state = cjson.decode(raw)
      if state.status ~= 'FROZEN' or state.binding ~= ARGV[1] or
          state.claimId ~= ARGV[2] or state.version ~= tonumber(ARGV[3]) or
          state.requestDigest ~= ARGV[4] then return 0 end
      local time = redis.call('TIME')
      local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
      if state.expires <= now then return 0 end
      return 1
      """,
          Long.class);

  private final StringRedisTemplate redis;
  private final String prefix;

  RedisPasswordResetCustody(StringRedisTemplate redis, String prefix) {
    this.redis = redis;
    this.prefix = prefix + "password-reset:causal:";
  }

  void store(String token, PasswordResetBinding binding) {
    String original = encodeBinding(binding);
    long now = Instant.now().toEpochMilli(), expiry = binding.expiresAt().toEpochMilli();
    if (expiry <= now || expiry > now + Duration.ofMinutes(120).toMillis()) throw invalid();
    var state = new TreeMap<String, Object>();
    state.put("binding", original);
    state.put("claimId", UUID.randomUUID().toString());
    state.put("version", 1);
    state.put("requestDigest", "");
    state.put("status", "ISSUED");
    state.put("delivery", "PENDING");
    state.put("expires", expiry);
    state.put("recoverUntil", expiry + RECOVERY_RETENTION.toMillis());
    state.put("locator", locator(token));
    state.put("subjectIndex", subject(binding.subjectId()));
    if (!Long.valueOf(1)
        .equals(
            redis.execute(
                STORE,
                List.of(flow(binding), locator(token), subject(binding.subjectId())),
                write(state),
                Long.toString(expiry)))) throw invalid();
  }

  Optional<PasswordResetBinding> issuance(String token) {
    String flow = redis.opsForValue().get(locator(token));
    if (flow == null) return Optional.empty();
    String raw = redis.opsForValue().get(flow);
    if (raw == null) return Optional.empty();
    JsonNode state = state(raw);
    if (!locator(token).equals(text(state, "locator"))) throw invalid();
    return Optional.of(binding(text(state, "binding")));
  }

  void delivery(PasswordResetBinding binding, String delivery) {
    if (!Set.of("ATTEMPTED", "SENT", "UNKNOWN").contains(delivery)
        || !Long.valueOf(1)
            .equals(
                redis.execute(DELIVERY, List.of(flow(binding)), encodeBinding(binding), delivery)))
      throw invalid();
  }

  Optional<PasswordResetClaim> claim(String token, String digest) {
    digest(digest);
    String raw = redis.execute(CLAIM, List.of(locator(token)), digest);
    return raw == null ? Optional.empty() : Optional.of(claim(state(raw)));
  }

  void requireCurrent(PasswordResetClaim claim) {
    validateClaim(claim);
    String raw = redis.opsForValue().get(flow(claim.binding()));
    if (raw == null) throw invalid();
    PasswordResetClaim actual = claim(state(raw));
    if (!actual.binding().equals(claim.binding())
        || !actual.claimId().equals(claim.claimId())
        || actual.version() != claim.version()
        || !actual.requestDigest().equals(claim.requestDigest())
        || !Set.of("FROZEN", "ACKNOWLEDGED", "REJECTED", "REFUSED").contains(actual.status()))
      throw invalid();
  }

  void requireFresh(PasswordResetClaim claim) {
    validateClaim(claim);
    if (!Long.valueOf(1)
        .equals(
            redis.execute(
                REQUIRE_FRESH,
                List.of(flow(claim.binding())),
                encodeBinding(claim.binding()),
                claim.claimId().toString(),
                Long.toString(claim.version()),
                claim.requestDigest()))) throw invalid();
  }

  void finish(PasswordResetClaim claim, boolean accepted) {
    finish(claim, accepted ? "ACKNOWLEDGED" : "REJECTED");
  }

  void refuse(PasswordResetClaim claim) {
    finish(claim, "REFUSED");
  }

  private void finish(PasswordResetClaim claim, String terminal) {
    validateClaim(claim);
    if (!Long.valueOf(1)
        .equals(
            redis.execute(
                FINISH,
                List.of(flow(claim.binding())),
                encodeBinding(claim.binding()),
                claim.claimId().toString(),
                Long.toString(claim.version()),
                claim.requestDigest(),
                terminal))) throw invalid();
  }

  private static void validateClaim(PasswordResetClaim claim) {
    if (claim == null || claim.claimId() == null || claim.version() <= 0) throw invalid();
    encodeBinding(claim.binding());
    digest(claim.requestDigest());
  }

  private PasswordResetClaim claim(JsonNode state) {
    return new PasswordResetClaim(
        binding(text(state, "binding")),
        uuid(text(state, "claimId")),
        positive(state, "version"),
        checkedDigest(text(state, "requestDigest")),
        text(state, "status"),
        text(state, "delivery"));
  }

  private static String encodeBinding(PasswordResetBinding b) {
    if (b == null
        || b.workflowId() == null
        || b.expiresAt() == null
        || b.expiresAt().toEpochMilli() <= 0
        || b.tenantId() != null && b.tenantId() < 0
        || !Set.of("USER", "CONSULTANT", "PLATFORM_ADMIN", "TENANT_ADMIN", "AGENCY_ADMIN")
            .contains(b.accountKind())) throw invalid();
    uuid(b.subjectId());
    bounded(b.username(), 255);
    bounded(b.accountVersion(), 512);
    digest(b.recipientDigest());
    var value = new TreeMap<String, Object>();
    value.put("workflowId", b.workflowId().toString());
    value.put("subjectId", b.subjectId());
    value.put("tenantId", b.tenantId());
    value.put("username", b.username());
    value.put("accountKind", b.accountKind());
    value.put("accountVersion", b.accountVersion());
    value.put("recipientDigest", b.recipientDigest());
    value.put("expiresAt", b.expiresAt().toString());
    return write(value);
  }

  private static PasswordResetBinding binding(String raw) {
    JsonNode value = read(raw);
    fields(
        value,
        Set.of(
            "workflowId",
            "subjectId",
            "tenantId",
            "username",
            "accountKind",
            "accountVersion",
            "recipientDigest",
            "expiresAt"));
    JsonNode tenant = value.get("tenantId");
    if (!tenant.isNull() && (!tenant.isIntegralNumber() || !tenant.canConvertToLong()))
      throw invalid();
    var b =
        new PasswordResetBinding(
            uuid(text(value, "workflowId")),
            text(value, "subjectId"),
            tenant.isNull() ? null : tenant.longValue(),
            text(value, "username"),
            text(value, "accountKind"),
            text(value, "accountVersion"),
            text(value, "recipientDigest"),
            Instant.parse(text(value, "expiresAt")));
    if (!encodeBinding(b).equals(raw)) throw invalid();
    return b;
  }

  private static JsonNode state(String raw) {
    JsonNode value = read(raw);
    fields(
        value,
        Set.of(
            "binding",
            "claimId",
            "version",
            "requestDigest",
            "status",
            "delivery",
            "expires",
            "recoverUntil",
            "locator",
            "subjectIndex"));
    long expiry = positive(value, "expires"), until = positive(value, "recoverUntil");
    if (until != expiry + RECOVERY_RETENTION.toMillis()) throw invalid();
    if (binding(text(value, "binding")).expiresAt().toEpochMilli() != expiry) throw invalid();
    if (!Set.of("ISSUED", "FROZEN", "REJECTED", "ACKNOWLEDGED", "REFUSED")
            .contains(text(value, "status"))
        || !Set.of("PENDING", "ATTEMPTED", "SENT", "UNKNOWN").contains(text(value, "delivery")))
      throw invalid();
    return value;
  }

  private String flow(PasswordResetBinding b) {
    encodeBinding(b);
    return prefix + "flow:" + b.workflowId();
  }

  private String subject(String id) {
    return prefix + "subject:" + sha(id);
  }

  private String locator(String token) {
    bounded(token, 4096);
    return prefix + "token:" + sha(token);
  }

  private static String sha(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw invalid();
    }
  }

  private static UUID uuid(String value) {
    try {
      UUID result = UUID.fromString(value);
      if (!result.toString().equals(value)) throw invalid();
      return result;
    } catch (RuntimeException invalid) {
      throw invalid();
    }
  }

  private static String checkedDigest(String value) {
    digest(value);
    return value;
  }

  private static void digest(String value) {
    if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid();
  }

  private static void bounded(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max) throw invalid();
  }

  private static String text(JsonNode value, String name) {
    JsonNode n = value.get(name);
    if (n == null || !n.isTextual()) throw invalid();
    return n.textValue();
  }

  private static long positive(JsonNode value, String name) {
    JsonNode n = value.get(name);
    if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() <= 0)
      throw invalid();
    return n.longValue();
  }

  private static void fields(JsonNode value, Set<String> expected) {
    var actual = new HashSet<String>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!value.isObject() || !actual.equals(expected)) throw invalid();
  }

  private static JsonNode read(String raw) {
    try {
      if (raw == null || raw.length() > 8192) throw invalid();
      return JSON.readTree(raw);
    } catch (java.io.IOException failure) {
      throw invalid();
    }
  }

  private static String write(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (java.io.IOException failure) {
      throw invalid();
    }
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Invalid password-reset custody");
  }
}
