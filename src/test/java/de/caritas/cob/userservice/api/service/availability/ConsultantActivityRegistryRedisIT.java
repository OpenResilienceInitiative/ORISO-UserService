package de.caritas.cob.userservice.api.service.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Local Docker contract test against the ORISO Redis container on localhost:6379. */
@EnabledIfEnvironmentVariable(named = "ORISO_LOCAL_REDIS_IT", matches = "true")
class ConsultantActivityRegistryRedisIT {

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate redisTemplate;
  private String keyPrefix;

  @BeforeEach
  void setUp() {
    connectionFactory = new LettuceConnectionFactory("localhost", 6379);
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    keyPrefix = "test:livechat:" + UUID.randomUUID() + ":";
  }

  @AfterEach
  void tearDown() {
    redisTemplate.delete(redisTemplate.keys(keyPrefix + "*"));
    connectionFactory.destroy();
  }

  @Test
  void availabilitySurvivesRegistryReconstructionExpiresAndHeartbeatCannotEnable() {
    var ttl = Duration.ofSeconds(2);
    var first =
        new ConsultantActivityRegistry(redisTemplate, new SimpleMeterRegistry(), ttl, keyPrefix);
    var reconstructed =
        new ConsultantActivityRegistry(redisTemplate, new SimpleMeterRegistry(), ttl, keyPrefix);

    first.markAvailable("consultant-1");
    assertThat(reconstructed.filterActive(List.of("consultant-1"), ttl.toMillis()))
        .containsExactly("consultant-1");

    reconstructed.markUnavailable("consultant-1");
    reconstructed.refreshIfAvailable("consultant-1");
    assertThat(first.filterActive(List.of("consultant-1"), ttl.toMillis())).isEmpty();

    first.markAvailable("consultant-1");
    await()
        .atMost(Duration.ofSeconds(4))
        .untilAsserted(
            () ->
                assertThat(reconstructed.filterActive(List.of("consultant-1"), ttl.toMillis()))
                    .isEmpty());
  }
}
