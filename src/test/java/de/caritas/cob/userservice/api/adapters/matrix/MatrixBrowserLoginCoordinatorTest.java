package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class MatrixBrowserLoginCoordinatorTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  @Test
  void coordinateFailsClosedWhenRedisCannotAcquireTheSafetyLock() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .thenThrow(new IllegalStateException("redis unavailable"));
    var coordinator =
        new MatrixBrowserLoginCoordinator(
            redisTemplate,
            new SimpleMeterRegistry(),
            Duration.ofSeconds(30),
            Duration.ofSeconds(1),
            Duration.ofMillis(25),
            "test:matrix:browser-login:");

    assertThatThrownBy(() -> coordinator.coordinate("@alice:example.org", () -> "must-not-run"))
        .isInstanceOf(MatrixBrowserLoginCoordinator.MatrixBrowserLoginCoordinationException.class)
        .hasMessage("Matrix browser login coordination store operation failed");
  }

  @Test
  void productionConfigurationRejectsLeaseAtTheMaximumSequentialHttpWait() {
    assertThatThrownBy(
            () ->
                new MatrixBrowserLoginCoordinator(
                    redisTemplate,
                    new SimpleMeterRegistry(),
                    26,
                    10_000,
                    "test:matrix:browser-login:"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Matrix browser login lease TTL must exceed PT26S");
  }
}
