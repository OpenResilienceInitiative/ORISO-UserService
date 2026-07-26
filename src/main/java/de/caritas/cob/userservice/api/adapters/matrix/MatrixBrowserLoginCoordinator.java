package de.caritas.cob.userservice.api.adapters.matrix;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Serializes one Matrix browser password-rotation/login pair across UserService replicas. */
@Component
@Slf4j
public class MatrixBrowserLoginCoordinator {

  static final String STORE_METRIC = "userservice.matrix.browser_login.coordination.operations";

  private static final Duration MAXIMUM_SEQUENTIAL_HTTP_WAIT = Duration.ofSeconds(26);

  private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] then "
              + "return redis.call('del', KEYS[1]) else return 0 end",
          Long.class);

  private final StringRedisTemplate redisTemplate;
  private final MeterRegistry meterRegistry;
  private final Duration leaseTtl;
  private final Duration waitLimit;
  private final Duration pollInterval;
  private final String keyPrefix;

  @Autowired
  public MatrixBrowserLoginCoordinator(
      StringRedisTemplate redisTemplate,
      MeterRegistry meterRegistry,
      @Value("${matrix.browser-login.coordination.leaseTtlSeconds:30}") long leaseTtlSeconds,
      @Value("${matrix.browser-login.coordination.waitLimitMillis:10000}") long waitLimitMillis,
      @Value("${matrix.browser-login.coordination.keyPrefix:matrix:browser-login:userservice:v1:}")
          String keyPrefix) {
    this(
        redisTemplate,
        meterRegistry,
        validatedProductionLeaseTtl(leaseTtlSeconds),
        Duration.ofMillis(waitLimitMillis),
        Duration.ofMillis(25),
        keyPrefix);
  }

  MatrixBrowserLoginCoordinator(
      StringRedisTemplate redisTemplate,
      MeterRegistry meterRegistry,
      Duration leaseTtl,
      Duration waitLimit,
      Duration pollInterval,
      String keyPrefix) {
    if (leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative()) {
      throw new IllegalArgumentException("Matrix browser login lease TTL must be positive");
    }
    if (waitLimit == null || waitLimit.isNegative()) {
      throw new IllegalArgumentException("Matrix browser login wait limit must not be negative");
    }
    if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
      throw new IllegalArgumentException("Matrix browser login poll interval must be positive");
    }
    if (keyPrefix == null || keyPrefix.isBlank()) {
      throw new IllegalArgumentException("Matrix browser login key prefix must not be blank");
    }
    this.redisTemplate = redisTemplate;
    this.meterRegistry = meterRegistry;
    this.leaseTtl = leaseTtl;
    this.waitLimit = waitLimit;
    this.pollInterval = pollInterval;
    this.keyPrefix = keyPrefix;
  }

  private static Duration validatedProductionLeaseTtl(long leaseTtlSeconds) {
    Duration configuredTtl = Duration.ofSeconds(leaseTtlSeconds);
    if (configuredTtl.compareTo(MAXIMUM_SEQUENTIAL_HTTP_WAIT) <= 0) {
      throw new IllegalArgumentException(
          "Matrix browser login lease TTL must exceed " + MAXIMUM_SEQUENTIAL_HTTP_WAIT);
    }
    return configuredTtl;
  }

  public <T> T coordinate(String matrixUserId, Supplier<T> operation) {
    if (matrixUserId == null || matrixUserId.isBlank()) {
      throw new IllegalArgumentException("matrixUserId must not be blank");
    }
    if (operation == null) {
      throw new IllegalArgumentException("operation must not be null");
    }

    String lockKey = lockKey(matrixUserId);
    String ownerId = UUID.randomUUID().toString();
    acquire(lockKey, ownerId);
    try {
      return operation.get();
    } finally {
      release(lockKey, ownerId);
    }
  }

  private void acquire(String lockKey, String ownerId) {
    long deadline = System.nanoTime() + waitLimit.toNanos();
    boolean contended = false;
    while (true) {
      try {
        if (Boolean.TRUE.equals(
            redisTemplate.opsForValue().setIfAbsent(lockKey, ownerId, leaseTtl))) {
          record("acquire", contended ? "waited" : "success");
          return;
        }
      } catch (RuntimeException exception) {
        record("acquire", "failure");
        throw storeFailure(exception);
      }

      contended = true;
      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        record("acquire", "timeout");
        throw new MatrixBrowserLoginCoordinationException(
            "Timed out waiting for Matrix browser login coordination");
      }
      LockSupport.parkNanos(Math.min(pollInterval.toNanos(), remainingNanos));
      if (Thread.currentThread().isInterrupted()) {
        record("acquire", "interrupted");
        throw new MatrixBrowserLoginCoordinationException(
            "Interrupted while waiting for Matrix browser login coordination");
      }
    }
  }

  private void release(String lockKey, String ownerId) {
    try {
      Long released =
          redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(lockKey), ownerId);
      record("release", released != null && released == 1L ? "success" : "not-owner");
    } catch (RuntimeException exception) {
      record("release", "failure");
      log.warn("Matrix browser login lock release failed; TTL remains the safety bound");
    }
  }

  private String lockKey(String matrixUserId) {
    return keyPrefix + DigestUtils.sha256Hex(matrixUserId.getBytes(StandardCharsets.UTF_8));
  }

  private MatrixBrowserLoginCoordinationException storeFailure(RuntimeException cause) {
    log.warn("Matrix browser login coordination Redis acquisition failed; login fails closed");
    return new MatrixBrowserLoginCoordinationException(
        "Matrix browser login coordination store operation failed", cause);
  }

  private void record(String operation, String outcome) {
    meterRegistry.counter(STORE_METRIC, "operation", operation, "outcome", outcome).increment();
  }

  public static final class MatrixBrowserLoginCoordinationException extends RuntimeException {
    MatrixBrowserLoginCoordinationException(String message) {
      super(message);
    }

    MatrixBrowserLoginCoordinationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
