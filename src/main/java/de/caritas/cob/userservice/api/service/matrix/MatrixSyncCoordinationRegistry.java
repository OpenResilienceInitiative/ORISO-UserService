package de.caritas.cob.userservice.api.service.matrix;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis-backed lease and durable cursor for the single logical Matrix {@code /sync} consumer. */
@Component
@Slf4j
public class MatrixSyncCoordinationRegistry {

  static final String STORE_METRIC = "userservice.matrix.sync.coordination.operations";

  private static final DefaultRedisScript<Long> RENEW_SCRIPT =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] then "
              + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
          Long.class);
  private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] then "
              + "return redis.call('del', KEYS[1]) else return 0 end",
          Long.class);
  private static final DefaultRedisScript<Long> COMMIT_CURSOR_SCRIPT =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] then "
              + "redis.call('set', KEYS[2], ARGV[2]); "
              + "redis.call('pexpire', KEYS[1], ARGV[3]); "
              + "return 1 else return 0 end",
          Long.class);

  private final StringRedisTemplate redisTemplate;
  private final MeterRegistry meterRegistry;
  private final Duration leaseTtl;
  private final String leaseKey;
  private final String cursorKey;
  private final String ownerId;

  @Autowired
  public MatrixSyncCoordinationRegistry(
      StringRedisTemplate redisTemplate,
      MeterRegistry meterRegistry,
      @Value("${matrix.event-listener.redis.leaseTtlSeconds:90}") long leaseTtlSeconds,
      @Value("${matrix.event-listener.redis.keyPrefix:matrix:sync:userservice:}")
          String keyPrefix) {
    this(
        redisTemplate,
        meterRegistry,
        Duration.ofSeconds(Math.max(leaseTtlSeconds, 1)),
        keyPrefix,
        UUID.randomUUID().toString());
  }

  MatrixSyncCoordinationRegistry(
      StringRedisTemplate redisTemplate,
      MeterRegistry meterRegistry,
      Duration leaseTtl,
      String keyPrefix,
      String ownerId) {
    this.redisTemplate = redisTemplate;
    this.meterRegistry = meterRegistry;
    this.leaseTtl = leaseTtl;
    this.leaseKey = keyPrefix + "lease";
    this.cursorKey = keyPrefix + "cursor";
    this.ownerId = ownerId;
  }

  public boolean tryAcquireLease() {
    try {
      boolean acquired =
          Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(leaseKey, ownerId, leaseTtl));
      record("acquire", acquired ? "success" : "contended");
      return acquired;
    } catch (RuntimeException exception) {
      throw storeFailure("acquire", exception);
    }
  }

  public boolean renewLease() {
    try {
      boolean renewed =
          isOne(
              redisTemplate.execute(
                  RENEW_SCRIPT,
                  Collections.singletonList(leaseKey),
                  ownerId,
                  Long.toString(leaseTtl.toMillis())));
      record("renew", renewed ? "success" : "lost");
      return renewed;
    } catch (RuntimeException exception) {
      throw storeFailure("renew", exception);
    }
  }

  public boolean releaseLease() {
    try {
      boolean released =
          isOne(
              redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(leaseKey), ownerId));
      record("release", released ? "success" : "not-owner");
      return released;
    } catch (RuntimeException exception) {
      throw storeFailure("release", exception);
    }
  }

  public Optional<String> readCursor() {
    try {
      String cursor = redisTemplate.opsForValue().get(cursorKey);
      record("cursor-read", cursor == null ? "missing" : "success");
      return Optional.ofNullable(cursor);
    } catch (RuntimeException exception) {
      throw storeFailure("cursor-read", exception);
    }
  }

  /** Commits the next cursor and renews the lease atomically, but only for the current owner. */
  public boolean commitCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return false;
    }
    try {
      boolean committed =
          isOne(
              redisTemplate.execute(
                  COMMIT_CURSOR_SCRIPT,
                  java.util.List.of(leaseKey, cursorKey),
                  ownerId,
                  cursor,
                  Long.toString(leaseTtl.toMillis())));
      record("cursor-commit", committed ? "success" : "lost");
      return committed;
    } catch (RuntimeException exception) {
      throw storeFailure("cursor-commit", exception);
    }
  }

  private boolean isOne(Long value) {
    return value != null && value == 1L;
  }

  private MatrixSyncCoordinationException storeFailure(String operation, RuntimeException cause) {
    record(operation, "failure");
    log.warn(
        "Matrix sync coordination Redis operation {} failed; listener fails closed", operation);
    return new MatrixSyncCoordinationException(
        "Matrix sync coordination store operation failed", cause);
  }

  private void record(String operation, String outcome) {
    meterRegistry.counter(STORE_METRIC, "operation", operation, "outcome", outcome).increment();
  }

  public static final class MatrixSyncCoordinationException extends RuntimeException {
    MatrixSyncCoordinationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
