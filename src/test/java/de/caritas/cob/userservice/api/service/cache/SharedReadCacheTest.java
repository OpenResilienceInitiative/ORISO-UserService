package de.caritas.cob.userservice.api.service.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SharedReadCacheTest {

  @Test
  void constructorRejectsUnboundedCorrectnessCacheTtl() {
    var redisTemplate = mock(StringRedisTemplate.class);

    assertThatThrownBy(
            () ->
                new SharedReadCache(
                    redisTemplate,
                    new ObjectMapper(),
                    new SimpleMeterRegistry(),
                    Duration.ZERO,
                    "test:"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1 and 60 seconds");
    assertThatThrownBy(
            () ->
                new SharedReadCache(
                    redisTemplate,
                    new ObjectMapper(),
                    new SimpleMeterRegistry(),
                    Duration.ofSeconds(61),
                    "test:"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1 and 60 seconds");
  }

  @Test
  void redisFailureFallsBackToLoaderAndRecordsBoundedOutcomes() {
    var redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(anyString())).thenThrow(new IllegalStateException("redis down"));
    when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .thenThrow(new IllegalStateException("redis down"));
    doThrow(new IllegalStateException("redis down"))
        .when(valueOperations)
        .set(anyString(), anyString(), any(Duration.class));
    var meterRegistry = new SimpleMeterRegistry();
    var cache =
        new SharedReadCache(
            redisTemplate, new ObjectMapper(), meterRegistry, Duration.ofSeconds(60), "test:");
    var loaderCalls = new AtomicInteger();

    String result =
        cache.getOrLoad(
            SharedReadCache.CacheName.APPLICATION_SETTINGS,
            "tenant:7",
            String.class,
            () -> {
              loaderCalls.incrementAndGet();
              return "current";
            });

    assertThat(result).isEqualTo("current");
    assertThat(loaderCalls).hasValue(1);
    assertThat(
            meterRegistry
                .get(SharedReadCache.OPERATIONS_METRIC)
                .tag("cache", "application-settings")
                .tag("operation", "read")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get(SharedReadCache.OPERATIONS_METRIC)
                .tag("cache", "application-settings")
                .tag("operation", "write")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }
}
