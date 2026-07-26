package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
class ActiveViewRegistryRedisIT {

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate redisTemplate;
  private String keyPrefix;

  @BeforeEach
  void setUp() {
    connectionFactory = new LettuceConnectionFactory("localhost", 6379);
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    keyPrefix = "test:active-view:" + UUID.randomUUID() + ":";
  }

  @AfterEach
  void tearDown() {
    redisTemplate.delete(redisTemplate.keys(keyPrefix + "*"));
    connectionFactory.destroy();
  }

  @Test
  void activeViewSurvivesRegistryReconstructionCanBeClearedAndExpires() {
    var ttl = Duration.ofSeconds(2);
    var first = new ActiveViewRegistry(redisTemplate, new SimpleMeterRegistry(), ttl, keyPrefix);
    var second = new ActiveViewRegistry(redisTemplate, new SimpleMeterRegistry(), ttl, keyPrefix);
    var expected = new ActiveViewRegistry.ActiveView("!room:matrix.test", "$thread");

    first.update("user-1", expected.roomId(), expected.threadRootId(), true);
    assertThat(second.find("user-1")).contains(expected);

    second.update("user-1", expected.roomId(), expected.threadRootId(), false);
    assertThat(first.find("user-1")).isEmpty();

    first.update("user-1", expected.roomId(), expected.threadRootId(), true);
    await()
        .atMost(Duration.ofSeconds(4))
        .untilAsserted(() -> assertThat(second.find("user-1")).isEmpty());
  }
}
