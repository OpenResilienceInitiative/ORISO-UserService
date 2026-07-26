package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "ORISO_LOCAL_REDIS_IT", matches = "true")
class MatrixSyncCoordinationRegistryRedisIT {

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate redisTemplate;
  private String keyPrefix;

  @BeforeEach
  void setUp() {
    connectionFactory = new LettuceConnectionFactory("localhost", 6379);
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    keyPrefix = "test:matrix-sync:" + UUID.randomUUID() + ":";
  }

  @AfterEach
  void tearDown() {
    redisTemplate.delete(redisTemplate.keys(keyPrefix + "*"));
    connectionFactory.destroy();
  }

  @Test
  void oneOwnerCommitsCursorAndSuccessorResumesFromIt() {
    var leaseTtl = Duration.ofSeconds(10);
    var first =
        new MatrixSyncCoordinationRegistry(
            redisTemplate, new SimpleMeterRegistry(), leaseTtl, keyPrefix, "owner-a");
    var second =
        new MatrixSyncCoordinationRegistry(
            redisTemplate, new SimpleMeterRegistry(), leaseTtl, keyPrefix, "owner-b");

    assertThat(first.tryAcquireLease()).isTrue();
    assertThat(second.tryAcquireLease()).isFalse();
    assertThat(first.commitCursor("batch-1")).isTrue();
    assertThat(second.commitCursor("stale-batch")).isFalse();
    assertThat(first.renewLease()).isTrue();

    assertThat(second.releaseLease()).isFalse();
    assertThat(first.releaseLease()).isTrue();
    assertThat(second.tryAcquireLease()).isTrue();
    assertThat(second.readCursor()).contains("batch-1");
    assertThat(first.commitCursor("stale-after-handover")).isFalse();
    assertThat(second.commitCursor("batch-2")).isTrue();
    assertThat(first.readCursor()).contains("batch-2");
  }
}
