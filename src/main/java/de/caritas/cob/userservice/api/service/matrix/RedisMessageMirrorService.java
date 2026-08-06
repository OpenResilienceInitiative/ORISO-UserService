package de.caritas.cob.userservice.api.service.matrix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Debug-only mirror for outgoing chat messages to Redis.
 *
 * <p>This is intentionally feature-flagged and TTL-bound so developers can inspect message flow in
 * Redis Commander without making Redis a source-of-truth for chat history.
 */
@Service
@Slf4j
@Profile("!prod")
@ConditionalOnExpression("${debug.redis.message.mirror.enabled:false}")
public class RedisMessageMirrorService {

  private static final int MESSAGE_HASH_HEX_CHARS = 8;

  private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
  private final ObjectMapper objectMapper;

  @Value("${debug.redis.message.mirror.enabled:false}")
  private boolean enabled;

  @Value("${debug.redis.message.mirror.ttlSeconds:900}")
  private long ttlSeconds;

  @Value("${debug.redis.message.mirror.maxBodyLength:500}")
  private int maxBodyLength;

  @Value("${debug.redis.message.mirror.keyPrefix:debug:msgmirror}")
  private String keyPrefix;

  public RedisMessageMirrorService(
      ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
    this.redisTemplateProvider = redisTemplateProvider;
    this.objectMapper = objectMapper;
    log.warn("RedisMessageMirrorService active — debug only, must never run in prod");
  }

  public void mirrorOutgoingMessage(
      Long sessionId,
      String roomId,
      String senderUsername,
      boolean consultantSender,
      String messageBody,
      String matrixEventId) {
    if (!enabled) {
      return;
    }

    if (messageBody == null || messageBody.isBlank()) {
      return;
    }

    StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
    if (redisTemplate == null) {
      log.warn("Redis message mirror enabled but no StringRedisTemplate is available.");
      return;
    }

    String safeBody =
        messageBody.length() > maxBodyLength
            ? messageBody.substring(0, maxBodyLength)
            : messageBody;
    String key =
        String.format(
            "%s:room:%s:%d:%s",
            keyPrefix,
            roomId,
            System.currentTimeMillis(),
            UUID.randomUUID().toString().substring(0, 8));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ts", Instant.now().toString());
    payload.put("sessionId", sessionId);
    payload.put("roomId", roomId);
    payload.put("messageLength", safeBody.length());
    payload.put("messageHash", sha256Prefix(safeBody, MESSAGE_HASH_HEX_CHARS));

    try {
      String serializedPayload = objectMapper.writeValueAsString(payload);
      redisTemplate
          .opsForValue()
          .set(key, serializedPayload, Duration.ofSeconds(Math.max(ttlSeconds, 30)));
    } catch (JsonProcessingException e) {
      log.warn("Failed to serialize Redis message mirror payload: {}", e.getMessage());
    } catch (Exception e) {
      log.warn("Failed writing mirrored message to Redis: {}", e.getMessage());
    }
  }

  private static String sha256Prefix(String input, int hexChars) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        hex.append(String.format("%02x", value));
      }
      return hex.substring(0, hexChars);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
