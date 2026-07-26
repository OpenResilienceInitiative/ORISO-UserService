package de.caritas.cob.userservice.api.service.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Redis-backed token storage shared by every UserService replica.
 *
 * <p>Token claim is an atomic Redis operation. Password-reset's one-token-per-account rule is
 * coordinated through a hashed subject index. Bearer tokens and account identifiers are hashed
 * before they enter Redis keys. Redis failures propagate to callers; authentication flows
 * deliberately fail closed instead of falling back to process-local memory.
 */
@Service
public class RedisOneTimeTokenStore implements OneTimeTokenStore {

  private static final String VALUE_SEPARATOR = ".";

  private static final DefaultRedisScript<Long> STORE_SCRIPT =
      new DefaultRedisScript<>(
          """
          local previous = redis.call('GET', KEYS[2])
          if previous then
            redis.call('DEL', previous)
          end
          redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
          redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
          return 1
          """,
          Long.class);

  private static final DefaultRedisScript<String> CLAIM_SCRIPT =
      new DefaultRedisScript<>(
          """
          local value = redis.call('GET', KEYS[1])
          if value then
            redis.call('DEL', KEYS[1])
          end
          return value
          """,
          String.class);

  private static final DefaultRedisScript<Long> RESTORE_SCRIPT =
      new DefaultRedisScript<>(
          """
          local current = redis.call('GET', KEYS[2])
          if current and current ~= ARGV[2] then
            return 0
          end
          redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
          redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
          return 1
          """,
          Long.class);

  private static final DefaultRedisScript<Long> CLEAR_INDEX_SCRIPT =
      new DefaultRedisScript<>(
          """
          if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
          end
          return 0
          """,
          Long.class);

  private final StringRedisTemplate redisTemplate;
  private final String keyPrefix;

  public RedisOneTimeTokenStore(
      StringRedisTemplate redisTemplate,
      @Value("${auth.one-time-token.redis.keyPrefix:auth:one-time:}") String keyPrefix) {
    this.redisTemplate = redisTemplate;
    this.keyPrefix = normalizePrefix(keyPrefix);
  }

  @Override
  public void store(
      String scope, String token, String subjectId, Instant expiresAt, boolean singlePerSubject) {
    validate(scope, token, subjectId, expiresAt);
    long ttlMillis = remainingTtlMillis(expiresAt);
    String tokenKey = tokenKey(scope, token);
    String value = encode(subjectId, expiresAt);

    if (singlePerSubject) {
      redisTemplate.execute(
          STORE_SCRIPT,
          List.of(tokenKey, subjectIndexKey(scope, subjectId)),
          value,
          tokenKey,
          Long.toString(ttlMillis));
    } else {
      redisTemplate.opsForValue().set(tokenKey, value, Duration.ofMillis(ttlMillis));
    }
  }

  @Override
  public Optional<TokenClaim> claim(String scope, String token) {
    requireSegment("scope", scope);
    requireSegment("token", token);
    String tokenKey = tokenKey(scope, token);
    String encoded = redisTemplate.execute(CLAIM_SCRIPT, List.of(tokenKey));
    if (encoded == null) {
      return Optional.empty();
    }

    TokenClaim claim = decode(encoded);
    clearSubjectIndexIfOwned(scope, claim.subjectId(), tokenKey);
    if (!claim.expiresAt().isAfter(Instant.now())) {
      return Optional.empty();
    }
    return Optional.of(claim);
  }

  @Override
  public boolean restore(String scope, String token, TokenClaim claim, boolean singlePerSubject) {
    validate(scope, token, claim.subjectId(), claim.expiresAt());
    long ttlMillis;
    try {
      ttlMillis = remainingTtlMillis(claim.expiresAt());
    } catch (IllegalArgumentException expired) {
      return false;
    }

    String tokenKey = tokenKey(scope, token);
    String value = encode(claim.subjectId(), claim.expiresAt());
    if (!singlePerSubject) {
      redisTemplate.opsForValue().set(tokenKey, value, Duration.ofMillis(ttlMillis));
      return true;
    }

    Long restored =
        redisTemplate.execute(
            RESTORE_SCRIPT,
            List.of(tokenKey, subjectIndexKey(scope, claim.subjectId())),
            value,
            tokenKey,
            Long.toString(ttlMillis));
    return Long.valueOf(1L).equals(restored);
  }

  @Override
  public void discard(String scope, String token, String subjectId) {
    requireSegment("scope", scope);
    requireSegment("token", token);
    requireSegment("subjectId", subjectId);
    String tokenKey = tokenKey(scope, token);
    redisTemplate.delete(tokenKey);
    clearSubjectIndexIfOwned(scope, subjectId, tokenKey);
  }

  private void clearSubjectIndexIfOwned(String scope, String subjectId, String tokenKey) {
    redisTemplate.execute(CLEAR_INDEX_SCRIPT, List.of(subjectIndexKey(scope, subjectId)), tokenKey);
  }

  private String tokenKey(String scope, String token) {
    return keyPrefix
        + requireSegment("scope", scope)
        + ":token:"
        + sha256(requireSegment("token", token));
  }

  private String subjectIndexKey(String scope, String subjectId) {
    return keyPrefix
        + requireSegment("scope", scope)
        + ":subject:"
        + sha256(requireSegment("subjectId", subjectId));
  }

  private static String encode(String subjectId, Instant expiresAt) {
    return expiresAt.truncatedTo(ChronoUnit.MILLIS).toEpochMilli()
        + VALUE_SEPARATOR
        + Base64.getUrlEncoder().withoutPadding().encodeToString(subjectId.getBytes(UTF_8));
  }

  private static TokenClaim decode(String encoded) {
    int separator = encoded.indexOf(VALUE_SEPARATOR);
    if (separator <= 0 || separator == encoded.length() - 1) {
      throw new IllegalStateException("Stored one-time token has an invalid format");
    }
    Instant expiresAt = Instant.ofEpochMilli(Long.parseLong(encoded.substring(0, separator)));
    String subjectId =
        new String(
            Base64.getUrlDecoder().decode(encoded.substring(separator + VALUE_SEPARATOR.length())),
            UTF_8);
    return new TokenClaim(subjectId, expiresAt);
  }

  private static long remainingTtlMillis(Instant expiresAt) {
    long ttlMillis = Duration.between(Instant.now(), expiresAt).toMillis();
    if (ttlMillis <= 0) {
      throw new IllegalArgumentException("One-time token expiry must be in the future");
    }
    return ttlMillis;
  }

  private static void validate(String scope, String token, String subjectId, Instant expiresAt) {
    requireSegment("scope", scope);
    requireSegment("token", token);
    requireSegment("subjectId", subjectId);
    if (expiresAt == null) {
      throw new IllegalArgumentException("expiresAt must not be null");
    }
  }

  private static String requireSegment(String name, String value) {
    if (value == null || value.isBlank() || value.contains(":")) {
      throw new IllegalArgumentException(name + " must be non-blank and must not contain ':'");
    }
    return value;
  }

  private static String normalizePrefix(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Redis one-time token key prefix must not be blank");
    }
    return value.endsWith(":") ? value : value + ":";
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
