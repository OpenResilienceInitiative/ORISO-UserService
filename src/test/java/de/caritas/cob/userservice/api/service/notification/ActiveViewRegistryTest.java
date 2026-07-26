package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ActiveViewRegistryTest {

  private static final Duration TTL = Duration.ofSeconds(30);
  private static final String PREFIX = "notification:active-view:";

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  private SimpleMeterRegistry meterRegistry;
  private ActiveViewRegistry registry;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    registry = new ActiveViewRegistry(redisTemplate, meterRegistry, TTL, PREFIX);
  }

  @Test
  void activeHeartbeatWritesOneTtlBoundValue() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    registry.update("user-1", "!room:matrix.test", "$thread", true);

    verify(valueOperations)
        .set(
            org.mockito.ArgumentMatchers.eq(PREFIX + "user-1"),
            any(String.class),
            org.mockito.ArgumentMatchers.eq(TTL));
  }

  @Test
  void anotherRegistryInstanceReadsRoomAndThreadFromSharedValue() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    registry.update("user-1", "!room:matrix.test", "$thread", true);
    var encoded = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(valueOperations)
        .set(
            org.mockito.ArgumentMatchers.eq(PREFIX + "user-1"),
            encoded.capture(),
            any(Duration.class));
    when(valueOperations.get(PREFIX + "user-1")).thenReturn(encoded.getValue());

    var reconstructed = new ActiveViewRegistry(redisTemplate, meterRegistry, TTL, PREFIX);

    assertThat(reconstructed.find("user-1"))
        .contains(new ActiveViewRegistry.ActiveView("!room:matrix.test", "$thread"));
  }

  @Test
  void inactiveHeartbeatDeletesStateImmediately() {
    registry.update("user-1", "!room:matrix.test", null, false);

    verify(redisTemplate).delete(PREFIX + "user-1");
  }

  @Test
  void readFailureFailsOpenAndExposesBoundedMetric() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(PREFIX + "user-1"))
        .thenThrow(new RedisConnectionFailureException("redis unavailable"));

    assertThat(registry.find("user-1")).isEmpty();
    assertThat(
            meterRegistry
                .get(ActiveViewRegistry.STORE_METRIC)
                .tag("operation", "read")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void invalidUserIdsNeverReachRedis() {
    registry.update(" ", "!room:matrix.test", null, true);

    assertThat(registry.find(null)).isEmpty();
    verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    verify(redisTemplate, never()).delete(any(String.class));
  }
}
