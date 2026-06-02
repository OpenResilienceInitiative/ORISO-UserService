package de.caritas.cob.userservice.api.service.matrix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
@RequiredArgsConstructor
public class RedisMessageMirrorService {

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

    String safeBody = messageBody.length() > maxBodyLength ? messageBody.substring(0, maxBodyLength) : messageBody;
    String key =
        String.format(
            "%s:room:%s:%d:%s",
            keyPrefix, roomId, System.currentTimeMillis(), UUID.randomUUID().toString().substring(0, 8));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ts", Instant.now().toString());
    payload.put("sessionId", sessionId);
    payload.put("roomId", roomId);
    payload.put("sender", senderUsername);
    payload.put("senderRole", consultantSender ? "consultant" : "user");
    payload.put("eventId", matrixEventId == null ? "" : matrixEventId);
    payload.put("message", safeBody);

    try {
      String serializedPayload = objectMapper.writeValueAsString(payload);
      redisTemplate.opsForValue().set(key, serializedPayload, Duration.ofSeconds(Math.max(ttlSeconds, 30)));
    } catch (JsonProcessingException e) {
      log.warn("Failed to serialize Redis message mirror payload: {}", e.getMessage());
    } catch (Exception e) {
      log.warn("Failed writing mirrored message to Redis: {}", e.getMessage());
    }
  }
}

