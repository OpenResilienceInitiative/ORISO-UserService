package de.caritas.cob.userservice.api.service.availability;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis-backed source of truth for consultants currently available for live chat. */
@Component
@Slf4j
public class ConsultantActivityRegistry {

  static final String STORE_METRIC = "oriso.live_chat.availability.store.operations";
  private static final String AVAILABLE_VALUE = "1";

  private final StringRedisTemplate redisTemplate;
  private final MeterRegistry meterRegistry;
  private final Duration ttl;
  private final String keyPrefix;

  @Autowired
  public ConsultantActivityRegistry(
      StringRedisTemplate redisTemplate,
      MeterRegistry meterRegistry,
      @Value("${consultant.availability.redis.ttlSeconds:120}") long ttlSeconds,
      @Value("${consultant.availability.redis.keyPrefix:livechat:consultant:available:}")
          String keyPrefix) {
    this(redisTemplate, meterRegistry, Duration.ofSeconds(ttlSeconds), keyPrefix);
  }

  ConsultantActivityRegistry(
      StringRedisTemplate redisTemplate,
      MeterRegistry meterRegistry,
      Duration ttl,
      String keyPrefix) {
    this.redisTemplate = redisTemplate;
    this.meterRegistry = meterRegistry;
    this.ttl = ttl;
    this.keyPrefix = keyPrefix;
  }

  /** Explicit enable. The request fails when Redis cannot acknowledge the state change. */
  public void markAvailable(String consultantId) {
    if (!isValid(consultantId)) {
      return;
    }
    try {
      redisTemplate.opsForValue().set(key(consultantId), AVAILABLE_VALUE, ttl);
      record("enable", "success");
    } catch (RuntimeException ex) {
      throw storeFailure("enable", ex);
    }
  }

  /** Explicit disable/logout. The Redis key is removed immediately. */
  public void markUnavailable(String consultantId) {
    if (!isValid(consultantId)) {
      return;
    }
    try {
      redisTemplate.delete(key(consultantId));
      record("disable", "success");
    } catch (RuntimeException ex) {
      throw storeFailure("disable", ex);
    }
  }

  /** Heartbeat: EXPIRE refreshes only an existing key and can never enable a consultant. */
  public void refreshIfAvailable(String consultantId) {
    if (!isValid(consultantId)) {
      return;
    }
    try {
      Boolean refreshed = redisTemplate.expire(key(consultantId), ttl);
      record("refresh", Boolean.TRUE.equals(refreshed) ? "success" : "missing");
    } catch (RuntimeException ex) {
      throw storeFailure("refresh", ex);
    }
  }

  /**
   * Returns only IDs with a live Redis key. Redis TTL is authoritative; {@code windowMs} remains in
   * the signature while existing callers migrate from the former process-local timestamp store. Any
   * Redis read failure fails closed, so no consultant is falsely routed.
   */
  public Set<String> filterActive(Collection<String> consultantIds, long windowMs) {
    if (consultantIds == null || consultantIds.isEmpty()) {
      return Collections.emptySet();
    }
    List<String> distinctIds = consultantIds.stream().filter(this::isValid).distinct().toList();
    if (distinctIds.isEmpty()) {
      return Collections.emptySet();
    }
    List<String> keys = distinctIds.stream().map(this::key).toList();
    try {
      List<String> values = redisTemplate.opsForValue().multiGet(keys);
      if (values == null) {
        record("read", "success");
        return Collections.emptySet();
      }
      Set<String> active = new LinkedHashSet<>();
      int resultSize = Math.min(distinctIds.size(), values.size());
      for (int index = 0; index < resultSize; index++) {
        if (AVAILABLE_VALUE.equals(values.get(index))) {
          active.add(distinctIds.get(index));
        }
      }
      record("read", "success");
      return active;
    } catch (RuntimeException ex) {
      record("read", "failure");
      log.warn("Live-chat availability Redis read failed; routing fails closed", ex);
      return Collections.emptySet();
    }
  }

  private AvailabilityStoreException storeFailure(String operation, RuntimeException cause) {
    record(operation, "failure");
    log.warn("Live-chat availability Redis {} failed", operation, cause);
    return new AvailabilityStoreException("Live-chat availability store operation failed", cause);
  }

  private void record(String operation, String outcome) {
    meterRegistry.counter(STORE_METRIC, "operation", operation, "outcome", outcome).increment();
  }

  private boolean isValid(String consultantId) {
    return consultantId != null && !consultantId.isBlank();
  }

  private String key(String consultantId) {
    return keyPrefix + consultantId;
  }
}
