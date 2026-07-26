package de.caritas.cob.userservice.api.service.notification;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed, TTL-bounded source of truth for notification active-view suppression.
 *
 * <p>The frontend refreshes active views every ten seconds. A missed inactive request can therefore
 * suppress notifications for at most the configured TTL. Redis read failures fail open so a
 * notification can become noisy, but cannot disappear because one replica lost access to the shared
 * state.
 */
@Component
@Slf4j
public class ActiveViewRegistry {

  static final String STORE_METRIC = "userservice.notification.active_view.store.operations";

  private final StringRedisTemplate redisTemplate;
  private final MeterRegistry meterRegistry;
  private final Duration ttl;
  private final String keyPrefix;

  @Autowired
  public ActiveViewRegistry(
      StringRedisTemplate redisTemplate,
      MeterRegistry meterRegistry,
      @Value("${notification.activeView.redis.ttlSeconds:30}") long ttlSeconds,
      @Value("${notification.activeView.redis.keyPrefix:notification:active-view:}")
          String keyPrefix) {
    this(redisTemplate, meterRegistry, Duration.ofSeconds(ttlSeconds), keyPrefix);
  }

  ActiveViewRegistry(
      StringRedisTemplate redisTemplate,
      MeterRegistry meterRegistry,
      Duration ttl,
      String keyPrefix) {
    this.redisTemplate = redisTemplate;
    this.meterRegistry = meterRegistry;
    this.ttl = ttl;
    this.keyPrefix = keyPrefix;
  }

  public void update(String userId, String roomId, String threadRootId, boolean active) {
    if (!isValid(userId)) {
      return;
    }
    if (!active || !isValid(roomId)) {
      delete(userId);
      return;
    }
    try {
      redisTemplate.opsForValue().set(key(userId), encode(roomId, threadRootId), ttl);
      record("write", "success");
    } catch (RuntimeException exception) {
      record("write", "failure");
      log.warn("Notification active-view Redis write failed; suppression remains fail-open");
    }
  }

  public Optional<ActiveView> find(String userId) {
    if (!isValid(userId)) {
      return Optional.empty();
    }
    try {
      String value = redisTemplate.opsForValue().get(key(userId));
      if (value == null) {
        record("read", "missing");
        return Optional.empty();
      }
      Optional<ActiveView> activeView = decode(value);
      record("read", activeView.isPresent() ? "success" : "invalid");
      return activeView;
    } catch (RuntimeException exception) {
      record("read", "failure");
      log.warn("Notification active-view Redis read failed; suppression fails open");
      return Optional.empty();
    }
  }

  private void delete(String userId) {
    try {
      redisTemplate.delete(key(userId));
      record("delete", "success");
    } catch (RuntimeException exception) {
      record("delete", "failure");
      log.warn("Notification active-view Redis delete failed; TTL remains the safety bound");
    }
  }

  private String encode(String roomId, String threadRootId) {
    return encodePart(roomId) + "." + encodePart(threadRootId);
  }

  private Optional<ActiveView> decode(String value) {
    String[] parts = value.split("\\.", -1);
    if (parts.length != 2 || parts[0].isBlank()) {
      return Optional.empty();
    }
    try {
      String roomId = decodePart(parts[0]);
      String threadRootId = parts[1].isBlank() ? null : decodePart(parts[1]);
      return Optional.of(new ActiveView(roomId, threadRootId));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  private String encodePart(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private String decodePart(String value) {
    return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }

  private boolean isValid(String value) {
    return value != null && !value.isBlank();
  }

  private String key(String userId) {
    return keyPrefix + userId;
  }

  private void record(String operation, String outcome) {
    meterRegistry.counter(STORE_METRIC, "operation", operation, "outcome", outcome).increment();
  }

  public record ActiveView(String roomId, String threadRootId) {}
}
