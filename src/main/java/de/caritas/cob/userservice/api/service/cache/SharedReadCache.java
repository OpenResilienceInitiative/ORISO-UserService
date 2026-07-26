package de.caritas.cob.userservice.api.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Shared, TTL-bounded cache for external reads whose stale values can change UserService behavior.
 *
 * <p>Redis failures fail open to the upstream loader. Cache names are a fixed enum so metrics never
 * contain tenant IDs, subdomains or other unbounded values.
 */
@Component
@Slf4j
public class SharedReadCache {

  public static final String OPERATIONS_METRIC = "userservice.shared_read_cache.operations";
  private static final Duration MAXIMUM_TTL = Duration.ofSeconds(60);
  private static final Duration LOAD_LOCK_TTL = Duration.ofSeconds(15);
  private static final Duration LOAD_WAIT_LIMIT = Duration.ofSeconds(14);
  private static final long LOAD_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
  private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] "
              + "then return redis.call('del', KEYS[1]) else return 0 end",
          Long.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final Duration ttl;
  private final String keyPrefix;

  @Autowired
  public SharedReadCache(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      @Value("${cache.shared.correctness.timeToLiveSeconds:60}") long ttlSeconds,
      @Value("${cache.shared.correctness.keyPrefix:userservice:shared-read:v1:}")
          String keyPrefix) {
    this(redisTemplate, objectMapper, meterRegistry, Duration.ofSeconds(ttlSeconds), keyPrefix);
  }

  SharedReadCache(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      Duration ttl,
      String keyPrefix) {
    if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAXIMUM_TTL) > 0) {
      throw new IllegalArgumentException(
          "Shared correctness cache TTL must be between 1 and 60 seconds");
    }
    if (keyPrefix == null || keyPrefix.isBlank()) {
      throw new IllegalArgumentException("Shared correctness cache key prefix must not be blank");
    }
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.ttl = ttl;
    this.keyPrefix = keyPrefix;
  }

  public <T> T getOrLoad(
      CacheName cacheName, String logicalKey, Class<T> valueType, Supplier<T> loader) {
    String redisKey = redisKey(cacheName, logicalKey);
    Optional<T> cached = read(cacheName, redisKey, valueType, true);
    if (cached.isPresent()) {
      return cached.get();
    }

    String lockOwner = UUID.randomUUID().toString();
    LockAttempt lockAttempt = tryAcquireLoadLock(cacheName, redisKey, lockOwner);
    if (lockAttempt == LockAttempt.ACQUIRED) {
      try {
        Optional<T> filledBeforeLoad = read(cacheName, redisKey, valueType, false);
        return filledBeforeLoad.orElseGet(() -> loadAndPut(cacheName, redisKey, loader));
      } finally {
        releaseLoadLock(cacheName, redisKey, lockOwner);
      }
    }
    if (lockAttempt == LockAttempt.CONTENDED) {
      Optional<T> shared = waitForLoad(cacheName, redisKey, valueType);
      if (shared.isPresent()) {
        return shared.get();
      }
    }
    return loadAndPut(cacheName, redisKey, loader);
  }

  private <T> T loadAndPut(CacheName cacheName, String redisKey, Supplier<T> loader) {
    T loaded = loader.get();
    if (loaded != null) {
      putAtRedisKey(cacheName, redisKey, loaded);
    }
    return loaded;
  }

  public void put(CacheName cacheName, String logicalKey, Object value) {
    putAtRedisKey(cacheName, redisKey(cacheName, logicalKey), value);
  }

  private void putAtRedisKey(CacheName cacheName, String redisKey, Object value) {
    if (value == null) {
      return;
    }
    try {
      redisTemplate.opsForValue().set(redisKey, objectMapper.writeValueAsString(value), ttl);
      record(cacheName, "write", "success");
    } catch (RuntimeException | JsonProcessingException exception) {
      record(cacheName, "write", "failure");
      log.warn(
          "Shared {} cache write failed; continuing without cached state", cacheName.metricTag());
    }
  }

  private <T> Optional<T> read(
      CacheName cacheName, String redisKey, Class<T> valueType, boolean recordOutcome) {
    try {
      String serialized = redisTemplate.opsForValue().get(redisKey);
      if (serialized == null) {
        if (recordOutcome) {
          record(cacheName, "read", "miss");
        }
        return Optional.empty();
      }
      T value = objectMapper.readValue(serialized, valueType);
      if (recordOutcome) {
        record(cacheName, "read", "hit");
      }
      return Optional.ofNullable(value);
    } catch (RuntimeException | JsonProcessingException exception) {
      if (recordOutcome) {
        record(cacheName, "read", "failure");
        log.warn("Shared {} cache read failed; falling back to upstream", cacheName.metricTag());
      }
      return Optional.empty();
    }
  }

  private LockAttempt tryAcquireLoadLock(CacheName cacheName, String redisKey, String lockOwner) {
    try {
      Boolean acquired =
          redisTemplate.opsForValue().setIfAbsent(loadLockKey(redisKey), lockOwner, LOAD_LOCK_TTL);
      LockAttempt attempt =
          Boolean.TRUE.equals(acquired) ? LockAttempt.ACQUIRED : LockAttempt.CONTENDED;
      record(cacheName, "load-lock", attempt.metricOutcome);
      return attempt;
    } catch (RuntimeException exception) {
      record(cacheName, "load-lock", "failure");
      log.warn(
          "Shared {} cache load lock failed; loading directly from upstream",
          cacheName.metricTag());
      return LockAttempt.FAILED;
    }
  }

  private <T> Optional<T> waitForLoad(CacheName cacheName, String redisKey, Class<T> valueType) {
    long deadline = System.nanoTime() + LOAD_WAIT_LIMIT.toNanos();
    while (System.nanoTime() < deadline) {
      LockSupport.parkNanos(LOAD_POLL_NANOS);
      if (Thread.currentThread().isInterrupted()) {
        record(cacheName, "load-wait", "interrupted");
        return Optional.empty();
      }
      Optional<T> shared = read(cacheName, redisKey, valueType, false);
      if (shared.isPresent()) {
        record(cacheName, "load-wait", "shared");
        return shared;
      }
    }
    record(cacheName, "load-wait", "timeout");
    return Optional.empty();
  }

  private void releaseLoadLock(CacheName cacheName, String redisKey, String lockOwner) {
    try {
      redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(loadLockKey(redisKey)), lockOwner);
      record(cacheName, "load-unlock", "success");
    } catch (RuntimeException exception) {
      record(cacheName, "load-unlock", "failure");
      log.warn(
          "Shared {} cache load lock release failed; TTL remains the safety bound",
          cacheName.metricTag());
    }
  }

  private String loadLockKey(String redisKey) {
    return redisKey + ":load-lock";
  }

  private String redisKey(CacheName cacheName, String logicalKey) {
    String encodedKey =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(String.valueOf(logicalKey).getBytes(StandardCharsets.UTF_8));
    return keyPrefix + cacheName.keySegment() + ":" + encodedKey;
  }

  private void record(CacheName cacheName, String operation, String outcome) {
    meterRegistry
        .counter(
            OPERATIONS_METRIC,
            "cache",
            cacheName.metricTag(),
            "operation",
            operation,
            "outcome",
            outcome)
        .increment();
  }

  public enum CacheName {
    APPLICATION_SETTINGS("application-settings"),
    TENANT("tenant"),
    TENANT_ADMIN("tenant-admin");

    private final String keySegment;

    CacheName(String keySegment) {
      this.keySegment = keySegment;
    }

    String keySegment() {
      return keySegment;
    }

    String metricTag() {
      return keySegment;
    }
  }

  private enum LockAttempt {
    ACQUIRED("acquired"),
    CONTENDED("contended"),
    FAILED("failure");

    private final String metricOutcome;

    LockAttempt(String metricOutcome) {
      this.metricOutcome = metricOutcome;
    }
  }
}
