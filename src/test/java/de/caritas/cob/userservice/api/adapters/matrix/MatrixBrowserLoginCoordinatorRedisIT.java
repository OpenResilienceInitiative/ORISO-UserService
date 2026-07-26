package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "ORISO_LOCAL_REDIS_IT", matches = "true")
class MatrixBrowserLoginCoordinatorRedisIT {

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate redisTemplate;
  private String keyPrefix;

  @BeforeEach
  void setUp() {
    connectionFactory = new LettuceConnectionFactory("localhost", 6379);
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    keyPrefix = "test:matrix:browser-login:" + UUID.randomUUID() + ":";
  }

  @AfterEach
  void tearDown() {
    redisTemplate.delete(redisTemplate.keys(keyPrefix + "*"));
    connectionFactory.destroy();
  }

  @Test
  void twoReplicaCoordinatorsSerializeOneUsersPasswordRotationAndLogin() throws Exception {
    var first = coordinator(Duration.ofSeconds(5), Duration.ofSeconds(3));
    var second = coordinator(Duration.ofSeconds(5), Duration.ofSeconds(3));
    var firstOperationEntered = new CountDownLatch(1);
    var releaseFirstOperation = new CountDownLatch(1);
    var secondOperationEntered = new CountDownLatch(1);
    var activeOperations = new AtomicInteger();
    var maximumActiveOperations = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var firstResult =
          executor.submit(
              () ->
                  first.coordinate(
                      "@alice:example.org",
                      () -> {
                        enter(activeOperations, maximumActiveOperations);
                        firstOperationEntered.countDown();
                        await(releaseFirstOperation);
                        activeOperations.decrementAndGet();
                        return "first";
                      }));
      assertThat(firstOperationEntered.await(2, TimeUnit.SECONDS)).isTrue();

      var secondResult =
          executor.submit(
              () ->
                  second.coordinate(
                      "@alice:example.org",
                      () -> {
                        enter(activeOperations, maximumActiveOperations);
                        secondOperationEntered.countDown();
                        activeOperations.decrementAndGet();
                        return "second";
                      }));

      assertThat(secondOperationEntered.await(200, TimeUnit.MILLISECONDS))
          .as("the second replica must wait while the first owns the shared lock")
          .isFalse();

      releaseFirstOperation.countDown();
      assertThat(firstResult.get(3, TimeUnit.SECONDS)).isEqualTo("first");
      assertThat(secondResult.get(3, TimeUnit.SECONDS)).isEqualTo("second");
      assertThat(maximumActiveOperations).hasValue(1);
    } finally {
      releaseFirstOperation.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void expiredOwnerDoesNotBlockTheNextReplicaIndefinitely() {
    String matrixUserId = "@alice:example.org";
    String lockKey =
        keyPrefix + DigestUtils.sha256Hex(matrixUserId.getBytes(StandardCharsets.UTF_8));
    redisTemplate.opsForValue().set(lockKey, "dead-replica", Duration.ofMillis(150));

    String result =
        coordinator(Duration.ofSeconds(2), Duration.ofSeconds(1))
            .coordinate(matrixUserId, () -> "recovered");

    assertThat(result).isEqualTo("recovered");
  }

  private MatrixBrowserLoginCoordinator coordinator(Duration leaseTtl, Duration waitLimit) {
    return new MatrixBrowserLoginCoordinator(
        redisTemplate,
        new SimpleMeterRegistry(),
        leaseTtl,
        waitLimit,
        Duration.ofMillis(25),
        keyPrefix);
  }

  private void enter(AtomicInteger active, AtomicInteger maximum) {
    int current = active.incrementAndGet();
    maximum.accumulateAndGet(current, Math::max);
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(3, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for replica coordination proof");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
