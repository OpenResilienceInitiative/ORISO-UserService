package de.caritas.cob.userservice.api.service.auth;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisPasswordResetCustodyIT {
  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private LettuceConnectionFactory connection;
  private StringRedisTemplate redis;
  private String prefix;
  private RedisOneTimeTokenStore first, second;
  private final String digest = "a".repeat(64);

  @BeforeEach
  void before() {
    connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connection.afterPropertiesSet();
    redis = new StringRedisTemplate(connection);
    redis.afterPropertiesSet();
    prefix = "test:reset:" + UUID.randomUUID() + ":";
    first = new RedisOneTimeTokenStore(redis, prefix);
    second = new RedisOneTimeTokenStore(redis, prefix);
  }

  @AfterEach
  void after() {
    redis.delete(redis.keys(prefix + "*"));
    connection.destroy();
  }

  private PasswordResetBinding binding() {
    return new PasswordResetBinding(
        UUID.randomUUID(),
        "00000000-0000-4000-8000-000000000001",
        1L,
        "enc.original",
        "CONSULTANT",
        "2026-10-22T12:00:00",
        "b".repeat(64),
        Instant.ofEpochMilli(Instant.now().plusSeconds(120).toEpochMilli()));
  }

  private PasswordResetBinding sent(String token) {
    var binding = binding();
    assertThatCode(() -> first.storePasswordReset(token, binding)).doesNotThrowAnyException();
    first.passwordResetDelivery(binding, "ATTEMPTED");
    first.passwordResetDelivery(binding, "SENT");
    return binding;
  }

  @Test
  void originalIssuanceAndStableClaimSurviveReplicaReplacement() {
    var binding = sent("token");
    var claim = second.claimPasswordReset("token", digest).orElseThrow();
    assertThat(claim.binding()).isEqualTo(binding);
    assertThat(claim.version()).isEqualTo(1);
    assertThat(claim.claimId()).isNotNull();
    assertThat(first.claimPasswordReset("token", digest)).contains(claim);
    first.requirePasswordResetClaim(claim);
    assertThat(claim.toString()).doesNotContain(binding.subjectId(), digest);
  }

  @Test
  void unsentIssuanceCannotClaimButLostSmtpResponseDoesNotStrandReceivedToken() {
    var binding = binding();
    assertThatCode(() -> first.storePasswordReset("token", binding)).doesNotThrowAnyException();
    assertThat(first.claimPasswordReset("token", digest)).isEmpty();
    first.passwordResetDelivery(binding, "ATTEMPTED");
    first.passwordResetDelivery(binding, "UNKNOWN");
    assertThat(second.claimPasswordReset("token", digest).orElseThrow().delivery())
        .isEqualTo("UNKNOWN");
  }

  @Test
  void crashAfterMailBeforeSuccessMarkKeepsOnlyExactAttemptedOriginalMailbox() {
    var binding = binding();
    assertThatCode(() -> first.storePasswordReset("token", binding)).doesNotThrowAnyException();
    first.passwordResetDelivery(binding, "ATTEMPTED");
    var claimed = second.claimPasswordReset("token", digest).orElseThrow();
    assertThat(claimed.delivery()).isEqualTo("ATTEMPTED");
    assertThat(claimed.binding().recipientDigest()).isEqualTo(binding.recipientDigest());
    assertThatThrownBy(
            () ->
                second.passwordResetDelivery(
                    new PasswordResetBinding(
                        binding.workflowId(),
                        binding.subjectId(),
                        binding.tenantId(),
                        binding.username(),
                        binding.accountKind(),
                        binding.accountVersion(),
                        "c".repeat(64),
                        binding.expiresAt()),
                    "SENT"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void concurrentChangedPasswordsCannotBothOwnTheSameClaim() throws Exception {
    sent("token");
    var pool = Executors.newFixedThreadPool(2);
    try {
      var start = new CountDownLatch(1);
      var one =
          pool.submit(
              () -> {
                start.await();
                return first.claimPasswordReset("token", digest);
              });
      var two =
          pool.submit(
              () -> {
                start.await();
                return second.claimPasswordReset("token", "c".repeat(64));
              });
      start.countDown();
      assertThat(
              List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)).stream()
                  .filter(Optional::isPresent)
                  .count())
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void definiteRejectionAdvancesVersionWithoutExtendingOriginalExpiry() {
    var binding = sent("token");
    var initial = first.claimPasswordReset("token", digest).orElseThrow();
    second.finishPasswordResetClaim(initial, false);
    var retry = second.claimPasswordReset("token", "c".repeat(64)).orElseThrow();
    assertThat(retry.claimId()).isEqualTo(initial.claimId());
    assertThat(retry.version()).isEqualTo(2);
    assertThat(retry.binding()).isEqualTo(binding);
    assertThatThrownBy(() -> first.requirePasswordResetClaim(initial))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void acknowledgementCannotBeChangedOrReplayedWithAnotherPassword() {
    sent("token");
    var initial = first.claimPasswordReset("token", digest).orElseThrow();
    second.finishPasswordResetClaim(initial, true);
    var replay = first.claimPasswordReset("token", digest).orElseThrow();
    assertThat(replay.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(second.claimPasswordReset("token", "c".repeat(64))).isEmpty();
    assertThatThrownBy(() -> first.finishPasswordResetClaim(initial, false))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void newIssuanceRevokesUnclaimedOldTokenButPreservesExactUnknownCustody() {
    sent("old");
    sent("new");
    assertThat(first.claimPasswordReset("old", digest)).isEmpty();
    var held = second.claimPasswordReset("new", digest).orElseThrow();
    sent("newer");
    assertThat(first.claimPasswordReset("new", digest)).contains(held);
    assertThat(first.claimPasswordReset("new", "c".repeat(64))).isEmpty();
  }

  @Test
  void anUnclaimedExpiredIssuanceCannotBeReminted() {
    var original = binding();
    var expiring =
        new PasswordResetBinding(
            original.workflowId(),
            original.subjectId(),
            original.tenantId(),
            original.username(),
            original.accountKind(),
            original.accountVersion(),
            original.recipientDigest(),
            Instant.ofEpochMilli(Instant.now().plusSeconds(3).toEpochMilli()));
    first.storePasswordReset("expiring", expiring);
    first.passwordResetDelivery(expiring, "ATTEMPTED");
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(second.passwordResetIssuance("expiring")).isEmpty());
    assertThat(first.claimPasswordReset("expiring", digest)).isEmpty();
  }

  @Test
  void expiredUnknownRetainsOnlyItsExactOriginalCommandAndCannotAdvanceVersion() {
    var original = binding();
    var expiring =
        new PasswordResetBinding(
            original.workflowId(),
            original.subjectId(),
            original.tenantId(),
            original.username(),
            original.accountKind(),
            original.accountVersion(),
            original.recipientDigest(),
            Instant.ofEpochMilli(Instant.now().plusSeconds(3).toEpochMilli()));
    first.storePasswordReset("held", expiring);
    first.passwordResetDelivery(expiring, "ATTEMPTED");
    var claim = first.claimPasswordReset("held", digest).orElseThrow();
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .until(() -> !expiring.expiresAt().isAfter(Instant.now()));
    assertThat(second.claimPasswordReset("held", digest)).contains(claim);
    assertThat(second.claimPasswordReset("held", "c".repeat(64))).isEmpty();
    second.finishPasswordResetClaim(claim, false);
    assertThat(first.claimPasswordReset("held", digest)).isEmpty();
    assertThat(first.claimPasswordReset("held", "c".repeat(64))).isEmpty();
  }

  @Test
  void heldRecoveryRetentionIsBoundedAndOriginalTokenAndSubjectStayOutOfKeys() {
    sent("original-unguessable-token");
    first.claimPasswordReset("original-unguessable-token", digest).orElseThrow();
    var keys = redis.keys(prefix + "*");
    assertThat(keys).hasSize(3);
    for (String key : keys) {
      assertThat(key)
          .doesNotContain("original-unguessable-token", "00000000-0000-4000-8000-000000000001");
      long ttl = redis.getExpire(key, TimeUnit.SECONDS);
      assertThat(ttl).isBetween(1L, java.time.Duration.ofDays(7).plusMinutes(2).toSeconds());
      if (key.contains(":flow:") || key.contains(":token:")) {
        assertThat(ttl).isGreaterThan(java.time.Duration.ofDays(6).toSeconds());
      }
    }
  }

  @Test
  void lateOldAcknowledgementCannotClearOrRebindTheNewerOriginalToken() {
    sent("old-held");
    var old = first.claimPasswordReset("old-held", digest).orElseThrow();
    var newer = sent("new-live");
    second.finishPasswordResetClaim(old, true);
    var current = first.claimPasswordReset("new-live", "c".repeat(64)).orElseThrow();
    assertThat(current.binding()).isEqualTo(newer);
    assertThat(current.claimId()).isNotEqualTo(old.claimId());
    assertThat(first.claimPasswordReset("old-held", digest).orElseThrow().status())
        .isEqualTo("ACKNOWLEDGED");
  }
}
