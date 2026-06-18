package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

  @BeforeEach
  void setUp() {
    service = new RedisMessageMirrorService(redisTemplateProvider, objectMapper);
    ReflectionTestUtils.setField(service, "enabled", false);
    ReflectionTestUtils.setField(service, "ttlSeconds", 900L);
    ReflectionTestUtils.setField(service, "maxBodyLength", 500);
    ReflectionTestUtils.setField(service, "keyPrefix", "debug:msgmirror");
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
