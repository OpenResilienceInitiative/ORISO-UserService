package de.caritas.cob.userservice.api.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisOneTimeTokenStoreIT {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate redisTemplate;
  private String prefix;
  private RedisOneTimeTokenStore firstInstance;
  private RedisOneTimeTokenStore secondInstance;

  @BeforeEach
  void setUp() {
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    prefix = "test:auth:" + UUID.randomUUID() + ":";
    firstInstance = new RedisOneTimeTokenStore(redisTemplate, prefix);
    secondInstance = new RedisOneTimeTokenStore(redisTemplate, prefix);
  }

  @AfterEach
  void tearDown() {
    redisTemplate.delete(redisTemplate.keys(prefix + "*"));
    connectionFactory.destroy();
  }

  @Test
  void tokenCreatedByOneInstanceIsConsumedExactlyOnceByAnother() {
    Instant expiresAt = Instant.ofEpochMilli(Instant.now().plusSeconds(30).toEpochMilli());
    firstInstance.store("magic", "token-a", "account-a", expiresAt, false);

    assertThat(secondInstance.claim("magic", "token-a"))
        .contains(new OneTimeTokenStore.TokenClaim("account-a", expiresAt));
    assertThat(firstInstance.claim("magic", "token-a")).isEmpty();
  }

  @Test
  void newerSinglePerSubjectTokenInvalidatesOlderTokenAcrossInstances() {
    Instant expiresAt = Instant.ofEpochMilli(Instant.now().plusSeconds(30).toEpochMilli());
    firstInstance.store("reset", "old-token", "account-a", expiresAt, true);
    secondInstance.store("reset", "new-token", "account-a", expiresAt, true);

    assertThat(firstInstance.claim("reset", "old-token")).isEmpty();
    assertThat(firstInstance.claim("reset", "new-token"))
        .contains(new OneTimeTokenStore.TokenClaim("account-a", expiresAt));
  }

  @Test
  void claimCanBeRestoredOnlyForItsRemainingTtl() {
    Instant expiresAt = Instant.ofEpochMilli(Instant.now().plusSeconds(5).toEpochMilli());
    firstInstance.store("reset", "retry-token", "account-a", expiresAt, true);
    OneTimeTokenStore.TokenClaim claim = secondInstance.claim("reset", "retry-token").orElseThrow();

    await()
        .atMost(Duration.ofSeconds(4))
        .until(() -> !Instant.now().isBefore(expiresAt.minusSeconds(2)));

    assertThat(secondInstance.restore("reset", "retry-token", claim, true)).isTrue();
    assertThat(firstInstance.claim("reset", "retry-token")).contains(claim);

    assertThat(firstInstance.restore("reset", "retry-token", claim, true)).isTrue();
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(() -> assertThat(secondInstance.claim("reset", "retry-token")).isEmpty());
  }

  @Test
  void restoringAnOlderClaimCannotInvalidateANewerToken() {
    Instant expiresAt = Instant.ofEpochMilli(Instant.now().plusSeconds(30).toEpochMilli());
    firstInstance.store("reset", "old-token", "account-a", expiresAt, true);
    OneTimeTokenStore.TokenClaim oldClaim =
        secondInstance.claim("reset", "old-token").orElseThrow();
    secondInstance.store("reset", "new-token", "account-a", expiresAt, true);

    assertThat(firstInstance.restore("reset", "old-token", oldClaim, true)).isFalse();
    assertThat(firstInstance.claim("reset", "old-token")).isEmpty();
    assertThat(firstInstance.claim("reset", "new-token")).contains(oldClaim);
  }
}
