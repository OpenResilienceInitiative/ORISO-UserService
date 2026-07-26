package de.caritas.cob.userservice.api.service.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Two reconstructed UserService cache instances against the same Redis 7 state. */
@EnabledIfEnvironmentVariable(named = "ORISO_LOCAL_REDIS_IT", matches = "true")
class SharedReadCacheRedisIT {

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate redisTemplate;
  private String keyPrefix;

  @BeforeEach
  void setUp() {
    connectionFactory = new LettuceConnectionFactory("localhost", 6379);
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    keyPrefix = "test:userservice:shared-read:" + UUID.randomUUID() + ":";
  }

  @AfterEach
  void tearDown() {
    redisTemplate.delete(redisTemplate.keys(keyPrefix + "*"));
    connectionFactory.destroy();
  }

  @Test
  void reconstructedReplicaSharesValueAndReloadsItAfterBoundedTtl() {
    var ttl = Duration.ofSeconds(1);
    var objectMapper = new ObjectMapper().findAndRegisterModules();
    var first =
        new SharedReadCache(redisTemplate, objectMapper, new SimpleMeterRegistry(), ttl, keyPrefix);
    var reconstructed =
        new SharedReadCache(redisTemplate, objectMapper, new SimpleMeterRegistry(), ttl, keyPrefix);
    var upstream =
        new AtomicReference<>(new RestrictedTenantDTO().id(42L).subdomain("berlin").name("Old"));
    var upstreamCalls = new AtomicInteger();

    RestrictedTenantDTO loadedByFirst =
        first.getOrLoad(
            SharedReadCache.CacheName.TENANT,
            "id:42",
            RestrictedTenantDTO.class,
            () -> {
              upstreamCalls.incrementAndGet();
              return upstream.get();
            });
    RestrictedTenantDTO sharedWithSecond =
        reconstructed.getOrLoad(
            SharedReadCache.CacheName.TENANT,
            "id:42",
            RestrictedTenantDTO.class,
            () -> {
              upstreamCalls.incrementAndGet();
              return upstream.get();
            });

    assertThat(loadedByFirst.getName()).isEqualTo("Old");
    assertThat(sharedWithSecond.getName()).isEqualTo("Old");
    assertThat(upstreamCalls).hasValue(1);

    upstream.set(new RestrictedTenantDTO().id(42L).subdomain("berlin").name("Current"));

    await()
        .atMost(Duration.ofSeconds(4))
        .untilAsserted(
            () ->
                assertThat(
                        reconstructed
                            .getOrLoad(
                                SharedReadCache.CacheName.TENANT,
                                "id:42",
                                RestrictedTenantDTO.class,
                                () -> {
                                  upstreamCalls.incrementAndGet();
                                  return upstream.get();
                                })
                            .getName())
                    .isEqualTo("Current"));

    assertThat(
            first
                .getOrLoad(
                    SharedReadCache.CacheName.TENANT,
                    "id:42",
                    RestrictedTenantDTO.class,
                    () -> {
                      upstreamCalls.incrementAndGet();
                      return upstream.get();
                    })
                .getName())
        .isEqualTo("Current");
    assertThat(upstreamCalls).hasValue(2);
  }

  @Test
  void allProductionDtoTypesRoundTripAcrossReplica() {
    var objectMapper = new ObjectMapper().findAndRegisterModules();
    var first =
        new SharedReadCache(
            redisTemplate,
            objectMapper,
            new SimpleMeterRegistry(),
            Duration.ofSeconds(60),
            keyPrefix);
    var reconstructed =
        new SharedReadCache(
            redisTemplate,
            objectMapper,
            new SimpleMeterRegistry(),
            Duration.ofSeconds(60),
            keyPrefix);
    var applicationSettings =
        new ApplicationSettingsDTO().releaseToggles(Map.of("groupChat", true));
    var tenantAdmin = new TenantDTO().id(42L).name("Berlin").subdomain("berlin");

    first.put(SharedReadCache.CacheName.APPLICATION_SETTINGS, "tenant:7", applicationSettings);
    first.put(SharedReadCache.CacheName.TENANT_ADMIN, "id:42", tenantAdmin);

    ApplicationSettingsDTO sharedSettings =
        reconstructed.getOrLoad(
            SharedReadCache.CacheName.APPLICATION_SETTINGS,
            "tenant:7",
            ApplicationSettingsDTO.class,
            SharedReadCacheRedisIT::unexpectedLoad);
    TenantDTO sharedTenantAdmin =
        reconstructed.getOrLoad(
            SharedReadCache.CacheName.TENANT_ADMIN,
            "id:42",
            TenantDTO.class,
            SharedReadCacheRedisIT::unexpectedLoad);

    assertThat(sharedSettings.getReleaseToggles()).containsEntry("groupChat", true);
    assertThat(sharedTenantAdmin.getId()).isEqualTo(42L);
    assertThat(sharedTenantAdmin.getSubdomain()).isEqualTo("berlin");
  }

  @Test
  void referenceDataListsRoundTripAcrossReplicasAndStayTenantIsolated() {
    var objectMapper = new ObjectMapper().findAndRegisterModules();
    var first =
        new SharedReadCache(
            redisTemplate,
            objectMapper,
            new SimpleMeterRegistry(),
            Duration.ofSeconds(60),
            keyPrefix);
    var second =
        new SharedReadCache(
            redisTemplate,
            objectMapper,
            new SimpleMeterRegistry(),
            Duration.ofSeconds(60),
            keyPrefix);
    var agenciesType = new TypeReference<List<AgencyDTO>>() {};
    var consultingTypesType = new TypeReference<List<ExtendedConsultingTypeResponseDTO>>() {};
    var topicsType = new TypeReference<List<TopicDTO>>() {};

    first.put(
        SharedReadCache.CacheName.AGENCY,
        "tenant:7:ids:42",
        List.of(new AgencyDTO().id(42L).name("Berlin")));
    first.put(
        SharedReadCache.CacheName.CONSULTING_TYPE,
        "tenant:7:all",
        List.of(new ExtendedConsultingTypeResponseDTO().id(1).slug("general")));
    first.put(
        SharedReadCache.CacheName.TOPIC,
        "tenant:7:active",
        List.of(new TopicDTO().id(9L).name("Housing")));

    List<AgencyDTO> sharedAgencies =
        second.getOrLoadTyped(
            SharedReadCache.CacheName.AGENCY,
            "tenant:7:ids:42",
            agenciesType,
            SharedReadCacheRedisIT::unexpectedLoad);
    List<ExtendedConsultingTypeResponseDTO> sharedConsultingTypes =
        second.getOrLoadTyped(
            SharedReadCache.CacheName.CONSULTING_TYPE,
            "tenant:7:all",
            consultingTypesType,
            SharedReadCacheRedisIT::unexpectedLoad);
    List<TopicDTO> sharedTopics =
        second.getOrLoadTyped(
            SharedReadCache.CacheName.TOPIC,
            "tenant:7:active",
            topicsType,
            SharedReadCacheRedisIT::unexpectedLoad);
    List<TopicDTO> otherTenantTopics =
        second.getOrLoadTyped(
            SharedReadCache.CacheName.TOPIC,
            "tenant:8:active",
            topicsType,
            () -> List.of(new TopicDTO().id(10L).name("Debt")));

    assertThat(sharedAgencies).extracting(AgencyDTO::getName).containsExactly("Berlin");
    assertThat(sharedConsultingTypes)
        .extracting(ExtendedConsultingTypeResponseDTO::getSlug)
        .containsExactly("general");
    assertThat(sharedTopics).extracting(TopicDTO::getName).containsExactly("Housing");
    assertThat(otherTenantTopics).extracting(TopicDTO::getName).containsExactly("Debt");
  }

  @Test
  void concurrentColdMissAcrossReplicasLoadsUpstreamOnlyOnce() throws Exception {
    var ttl = Duration.ofSeconds(60);
    var objectMapper = new ObjectMapper().findAndRegisterModules();
    var first =
        new SharedReadCache(redisTemplate, objectMapper, new SimpleMeterRegistry(), ttl, keyPrefix);
    var second =
        new SharedReadCache(redisTemplate, objectMapper, new SimpleMeterRegistry(), ttl, keyPrefix);
    var upstreamCalls = new AtomicInteger();
    var firstLoadStarted = new CountDownLatch(1);
    var releaseUpstream = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);

    try {
      var firstResult =
          executor.submit(
              () ->
                  first.getOrLoad(
                      SharedReadCache.CacheName.APPLICATION_SETTINGS,
                      "tenant:7",
                      String.class,
                      () -> {
                        upstreamCalls.incrementAndGet();
                        firstLoadStarted.countDown();
                        awaitLatch(releaseUpstream);
                        return "current";
                      }));
      assertThat(firstLoadStarted.await(2, TimeUnit.SECONDS)).isTrue();

      var secondResult =
          executor.submit(
              () ->
                  second.getOrLoad(
                      SharedReadCache.CacheName.APPLICATION_SETTINGS,
                      "tenant:7",
                      String.class,
                      () -> {
                        upstreamCalls.incrementAndGet();
                        return "duplicate";
                      }));

      await()
          .during(Duration.ofMillis(200))
          .atMost(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(upstreamCalls).hasValue(1));
      releaseUpstream.countDown();

      assertThat(firstResult.get(2, TimeUnit.SECONDS)).isEqualTo("current");
      assertThat(secondResult.get(2, TimeUnit.SECONDS)).isEqualTo("current");
      assertThat(upstreamCalls).hasValue(1);
    } finally {
      releaseUpstream.countDown();
      executor.shutdownNow();
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for test release");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for test release", exception);
    }
  }

  private static <T> T unexpectedLoad() {
    throw new AssertionError("Shared Redis value should prevent an upstream load");
  }
}
