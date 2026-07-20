package de.caritas.cob.userservice.api.service.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ConsultantActivityRegistryTest {

  private static final Duration TTL = Duration.ofSeconds(120);
  private static final String PREFIX = "livechat:consultant:available:";

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  private SimpleMeterRegistry meterRegistry;
  private ConsultantActivityRegistry registry;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    registry = new ConsultantActivityRegistry(redisTemplate, meterRegistry, TTL, PREFIX);
  }

  private void givenValueOperations() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  @Test
  void markAvailable_Should_WriteRedisKeyWithConfiguredTtl() {
    givenValueOperations();
    registry.markAvailable("consultant-1");

    verify(valueOperations).set(PREFIX + "consultant-1", "1", TTL);
  }

  @Test
  void newRegistryInstance_Should_ReadAvailabilityWrittenByPreviousInstance() {
    givenValueOperations();
    registry.markAvailable("consultant-1");
    when(valueOperations.multiGet(List.of(PREFIX + "consultant-1"))).thenReturn(List.of("1"));

    var reconstructedRegistry =
        new ConsultantActivityRegistry(redisTemplate, meterRegistry, TTL, PREFIX);

    assertThat(reconstructedRegistry.filterActive(List.of("consultant-1"), TTL.toMillis()))
        .containsExactly("consultant-1");
  }

  @Test
  void filterActive_Should_ExcludeExpiredRedisKey() {
    givenValueOperations();
    when(valueOperations.multiGet(List.of(PREFIX + "consultant-1")))
        .thenReturn(java.util.Collections.singletonList(null));

    assertThat(registry.filterActive(List.of("consultant-1"), TTL.toMillis())).isEmpty();
  }

  @Test
  void refreshIfAvailable_Should_OnlyExtendExistingKeyAndNeverCreateOne() {
    when(redisTemplate.expire(PREFIX + "consultant-1", TTL)).thenReturn(false);

    registry.refreshIfAvailable("consultant-1");

    verify(redisTemplate).expire(PREFIX + "consultant-1", TTL);
    verify(valueOperations, never()).set(any(), any(), any(Duration.class));
  }

  @Test
  void markUnavailable_Should_DeleteRedisKeyImmediately() {
    registry.markUnavailable("consultant-1");

    verify(redisTemplate).delete(PREFIX + "consultant-1");
  }

  @Test
  void filterActive_Should_FailClosedAndExposeMetric_WhenRedisReadFails() {
    givenValueOperations();
    when(valueOperations.multiGet(List.of(PREFIX + "consultant-1")))
        .thenThrow(new RedisConnectionFailureException("redis unavailable"));

    assertThat(registry.filterActive(List.of("consultant-1"), TTL.toMillis())).isEmpty();
    assertThat(
            meterRegistry
                .get(ConsultantActivityRegistry.STORE_METRIC)
                .tag("operation", "read")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void markAvailable_Should_RejectEnableAndExposeMetric_WhenRedisWriteFails() {
    givenValueOperations();
    org.mockito.Mockito.doThrow(new RedisConnectionFailureException("redis unavailable"))
        .when(valueOperations)
        .set(PREFIX + "consultant-1", "1", TTL);

    assertThatThrownBy(() -> registry.markAvailable("consultant-1"))
        .isInstanceOf(AvailabilityStoreException.class);
    assertThat(
            meterRegistry
                .get(ConsultantActivityRegistry.STORE_METRIC)
                .tag("operation", "enable")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void invalidIds_Should_NotAccessRedis() {
    registry.markAvailable(" ");
    registry.markUnavailable(null);
    registry.refreshIfAvailable(null);

    verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    verify(redisTemplate, never()).delete(any(String.class));
    verify(redisTemplate, never()).expire(any(String.class), any(Duration.class));
  }
}
