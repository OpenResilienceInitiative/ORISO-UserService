package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RedisMessageMirrorServiceTest {

  private static final String SENSITIVE_MESSAGE = "super-secret counselling text";
  private static final String SENDER_USERNAME = "alice.user";

  @Mock private ObjectProvider<StringRedisTemplate> redisTemplateProvider;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private RedisMessageMirrorService service;
  private Logger logger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    service = new RedisMessageMirrorService(redisTemplateProvider, objectMapper);
    ReflectionTestUtils.setField(service, "enabled", false);
    ReflectionTestUtils.setField(service, "ttlSeconds", 900L);
    ReflectionTestUtils.setField(service, "maxBodyLength", 500);
    ReflectionTestUtils.setField(service, "keyPrefix", "debug:msgmirror");

    logger = (Logger) LoggerFactory.getLogger(RedisMessageMirrorService.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logAppender);
  }

  @Test
  void mirrorOutgoingMessage_shouldNotWriteToRedisWhenDisabled() {
    assertThatCode(
            () ->
                service.mirrorOutgoingMessage(
                    42L,
                    "!room:example.org",
                    SENDER_USERNAME,
                    true,
                    SENSITIVE_MESSAGE,
                    "$event123"))
        .doesNotThrowAnyException();

    verifyNoInteractions(redisTemplateProvider);
    verifyNoInteractions(redisTemplate);
  }

  @Test
  void mirrorOutgoingMessage_shouldStoreMetadataOnlyWhenEnabled() throws Exception {
    ReflectionTestUtils.setField(service, "enabled", true);
    when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    service.mirrorOutgoingMessage(
        42L, "!room:example.org", SENDER_USERNAME, true, SENSITIVE_MESSAGE, "$event123");

    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(valueOperations).set(anyString(), payloadCaptor.capture(), any(Duration.class));

    String serializedPayload = payloadCaptor.getValue();
    JsonNode payload = objectMapper.readTree(serializedPayload);

    assertThat(payload.has("sessionId")).isTrue();
    assertThat(payload.get("sessionId").asLong()).isEqualTo(42L);
    assertThat(payload.has("messageLength")).isTrue();
    assertThat(payload.get("messageLength").asInt()).isEqualTo(SENSITIVE_MESSAGE.length());
    assertThat(payload.has("messageHash")).isTrue();
    assertThat(payload.get("messageHash").asText()).isEqualTo(sha256Prefix(SENSITIVE_MESSAGE, 8));

    assertThat(payload.has("message")).isFalse();
    assertThat(payload.has("sender")).isFalse();
    assertThat(payload.has("senderRole")).isFalse();
    assertThat(serializedPayload).doesNotContain(SENSITIVE_MESSAGE);
    assertThat(serializedPayload).doesNotContain(SENDER_USERNAME);
  }

  @Test
  void mirrorOutgoingMessage_shouldNotThrowWhenStringRedisTemplateUnavailable() {
    ReflectionTestUtils.setField(service, "enabled", true);
    when(redisTemplateProvider.getIfAvailable()).thenReturn(null);

    assertThatCode(
            () ->
                service.mirrorOutgoingMessage(
                    42L,
                    "!room:example.org",
                    SENDER_USERNAME,
                    true,
                    SENSITIVE_MESSAGE,
                    "$event123"))
        .doesNotThrowAnyException();

    verify(redisTemplateProvider).getIfAvailable();
    verify(redisTemplate, never()).opsForValue();
  }

  @Test
  void mirrorOutgoingMessage_shouldReturnEarly_whenMessageBodyIsNull() {
    // Empty timeline events must not create bogus Redis keys during debug mirroring.
    ReflectionTestUtils.setField(service, "enabled", true);

    service.mirrorOutgoingMessage(1L, "!room:example.org", SENDER_USERNAME, true, null, "$evt");

    verifyNoInteractions(redisTemplateProvider);
  }

  @Test
  void mirrorOutgoingMessage_shouldReturnEarly_whenMessageBodyIsBlank() {
    // Whitespace-only bodies carry no inspectable content and must be skipped.
    ReflectionTestUtils.setField(service, "enabled", true);

    service.mirrorOutgoingMessage(1L, "!room:example.org", SENDER_USERNAME, true, "   ", "$evt");

    verifyNoInteractions(redisTemplateProvider);
  }

  @Test
  void mirrorOutgoingMessage_shouldNotTruncate_whenBodyLengthEqualsMax() throws Exception {
    // Messages exactly at the limit must be hashed in full without off-by-one truncation.
    ReflectionTestUtils.setField(service, "enabled", true);
    ReflectionTestUtils.setField(service, "maxBodyLength", 10);
    when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    String body = "0123456789";

    service.mirrorOutgoingMessage(1L, "!room:example.org", SENDER_USERNAME, true, body, "$evt");

    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(valueOperations).set(anyString(), payloadCaptor.capture(), any(Duration.class));
    JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
    assertThat(payload.get("messageLength").asInt()).isEqualTo(10);
    assertThat(payload.get("messageHash").asText()).isEqualTo(sha256Prefix(body, 8));
  }

  @Test
  void mirrorOutgoingMessage_shouldTruncate_whenBodyLengthExceedsMax() throws Exception {
    // Oversized bodies must be capped so Redis payloads stay bounded in debug mode.
    ReflectionTestUtils.setField(service, "enabled", true);
    ReflectionTestUtils.setField(service, "maxBodyLength", 10);
    when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    String body = "01234567890";

    service.mirrorOutgoingMessage(1L, "!room:example.org", SENDER_USERNAME, true, body, "$evt");

    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(valueOperations).set(anyString(), payloadCaptor.capture(), any(Duration.class));
    JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
    assertThat(payload.get("messageLength").asInt()).isEqualTo(10);
    assertThat(payload.get("messageHash").asText())
        .isEqualTo(sha256Prefix(body.substring(0, 10), 8));
  }

  @Test
  void mirrorOutgoingMessage_shouldWarnAndNotThrow_whenRedisSetFails() {
    // Redis outages during debug mirroring must not surface to counselling message delivery.
    ReflectionTestUtils.setField(service, "enabled", true);
    when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    doThrow(new RuntimeException("redis down"))
        .when(valueOperations)
        .set(anyString(), anyString(), any(Duration.class));

    assertThatCode(
            () ->
                service.mirrorOutgoingMessage(
                    1L, "!room:example.org", SENDER_USERNAME, true, SENSITIVE_MESSAGE, "$evt"))
        .doesNotThrowAnyException();

    assertThat(logAppender.list)
        .anyMatch(
            e ->
                e.getLevel().toString().equals("WARN")
                    && e.getFormattedMessage().contains("Failed writing mirrored message"));
  }

  @Test
  void mirrorOutgoingMessage_shouldUseTtl30Seconds_whenConfiguredTtlIs29() {
    // TTL floor prevents keys expiring too quickly to be useful in Redis Commander.
    ReflectionTestUtils.setField(service, "enabled", true);
    ReflectionTestUtils.setField(service, "ttlSeconds", 29L);
    when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    service.mirrorOutgoingMessage(
        1L, "!room:example.org", SENDER_USERNAME, true, SENSITIVE_MESSAGE, "$evt");

    ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(valueOperations).set(anyString(), anyString(), ttlCaptor.capture());
    assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void mirrorOutgoingMessage_shouldUseTtl30Seconds_whenConfiguredTtlIsExactly30() {
    // Boundary ttl of 30 seconds is the minimum allowed retention for debug mirrors.
    ReflectionTestUtils.setField(service, "enabled", true);
    ReflectionTestUtils.setField(service, "ttlSeconds", 30L);
    when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    service.mirrorOutgoingMessage(
        1L, "!room:example.org", SENDER_USERNAME, true, SENSITIVE_MESSAGE, "$evt");

    ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(valueOperations).set(anyString(), anyString(), ttlCaptor.capture());
    assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void mirrorOutgoingMessage_shouldUseConfiguredTtl_whenAboveMinimum() {
    // Custom TTL above the floor must be honoured for longer debug inspection windows.
    ReflectionTestUtils.setField(service, "enabled", true);
    ReflectionTestUtils.setField(service, "ttlSeconds", 31L);
    when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    service.mirrorOutgoingMessage(
        1L, "!room:example.org", SENDER_USERNAME, true, SENSITIVE_MESSAGE, "$evt");

    ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(valueOperations).set(anyString(), anyString(), ttlCaptor.capture());
    assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofSeconds(31));
  }

  @Test
  void mirrorOutgoingMessage_shouldLogSerializationWarning_whenObjectMapperFails()
      throws Exception {
    // Serialization errors must be visible to developers without aborting message handling.
    ObjectMapper failingMapper = mock(ObjectMapper.class);
    when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
    var failingService = new RedisMessageMirrorService(redisTemplateProvider, failingMapper);
    ReflectionTestUtils.setField(failingService, "enabled", true);
    when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);

    Logger failingLogger = (Logger) LoggerFactory.getLogger(RedisMessageMirrorService.class);
    ListAppender<ILoggingEvent> failingAppender = new ListAppender<>();
    failingAppender.start();
    failingLogger.addAppender(failingAppender);

    try {
      assertThatCode(
              () ->
                  failingService.mirrorOutgoingMessage(
                      1L, "!room:example.org", SENDER_USERNAME, true, SENSITIVE_MESSAGE, "$evt"))
          .doesNotThrowAnyException();

      verify(redisTemplate, never()).opsForValue();
      assertThat(failingAppender.list)
          .anyMatch(
              e ->
                  e.getLevel().toString().equals("WARN")
                      && e.getFormattedMessage().contains("Failed to serialize"));
    } finally {
      failingLogger.detachAppender(failingAppender);
    }
  }

  private static String sha256Prefix(String input, int hexChars) throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder(hash.length * 2);
    for (byte value : hash) {
      hex.append(String.format("%02x", value));
    }
    return hex.substring(0, hexChars);
  }
}
