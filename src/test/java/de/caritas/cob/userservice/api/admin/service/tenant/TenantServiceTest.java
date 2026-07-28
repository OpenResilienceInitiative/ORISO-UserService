package de.caritas.cob.userservice.api.admin.service.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.config.CacheManagerConfig;
import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.tenantservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

class TenantServiceTest {

  private static final String SUBDOMAIN = "berlin";
  private static final Long TENANT_ID = 42L;

  private StubTenantControllerApi tenantControllerApi;
  private TenantService tenantService;

  @BeforeEach
  void setUp() {
    tenantControllerApi = new StubTenantControllerApi();
    tenantService =
        new TenantService(
            new StubTenantServiceApiControllerFactory(tenantControllerApi), testCacheManager());
  }

  // Tenant resolution must reach the external tenant service on a cache miss.
  @Test
  void getRestrictedTenantData_validSubdomain_returnsDtoFromApi() {
    var expected = new RestrictedTenantDTO().id(TENANT_ID).subdomain(SUBDOMAIN).name("Berlin");
    tenantControllerApi.subdomainResult = expected;

    RestrictedTenantDTO result = tenantService.getRestrictedTenantData(SUBDOMAIN);

    assertThat(result).isSameAs(expected);
    assertThat(tenantControllerApi.subdomainCalls.get()).isEqualTo(1);
  }

  // Admin and filter UIs resolve tenants by numeric id as well as subdomain.
  @Test
  void getRestrictedTenantData_validTenantId_returnsDtoFromApi() {
    var expected = new RestrictedTenantDTO().id(TENANT_ID).subdomain(SUBDOMAIN).name("Berlin");
    tenantControllerApi.tenantIdResult = expected;

    RestrictedTenantDTO result = tenantService.getRestrictedTenantData(TENANT_ID);

    assertThat(result).isSameAs(expected);
    assertThat(tenantControllerApi.tenantIdCalls.get()).isEqualTo(1);
  }

  @Test
  void getRestrictedTenantData_validTenantIds_returnsDtosFromOneBatchCall() {
    var knownTenant = new RestrictedTenantDTO().id(TENANT_ID).name("Berlin");
    tenantControllerApi.tenantIdsResult = List.of(knownTenant);

    var result = tenantService.getRestrictedTenantData(Set.of(TENANT_ID, 99L));

    assertThat(result).containsExactly(knownTenant);
    assertThat(tenantControllerApi.tenantIdsCalls.get()).isEqualTo(1);
    assertThat(tenantControllerApi.lastTenantIds).containsExactlyInAnyOrder(TENANT_ID, 99L);
    assertThat(tenantControllerApi.tenantIdCalls.get()).isZero();
  }

  @Test
  void getRestrictedTenantData_moreThanProviderLimit_partitionsIntoBoundedBatchCalls() {
    var tenantIds = new LinkedHashSet<Long>();
    for (long tenantId = 1; tenantId <= 201; tenantId++) {
      tenantIds.add(tenantId);
    }

    assertThat(tenantService.getRestrictedTenantData(tenantIds)).isEmpty();

    assertThat(tenantControllerApi.tenantIdsCalls.get()).isEqualTo(3);
    assertThat(tenantControllerApi.tenantIdRequests)
        .allSatisfy(request -> assertThat(request).hasSizeLessThanOrEqualTo(100));
    assertThat(tenantControllerApi.tenantIdRequests.stream().flatMap(List::stream))
        .containsExactlyElementsOf(tenantIds);
    assertThat(tenantControllerApi.tenantIdCalls.get()).isZero();
  }

  @Test
  void getRestrictedTenantData_emptyTenantIds_doesNotCallApi() {
    assertThat(tenantService.getRestrictedTenantData(Set.of())).isEmpty();
    assertThat(tenantControllerApi.tenantIdsCalls.get()).isZero();
    assertThat(tenantControllerApi.tenantIdCalls.get()).isZero();
  }

  // Callers such as HttpTenantFilter rely on upstream errors surfacing unchanged.
  @Test
  void getRestrictedTenantData_subdomainApiFailure_propagatesRestClientException() {
    tenantControllerApi.subdomainException = new RestClientException("tenant service down");

    assertThatThrownBy(() -> tenantService.getRestrictedTenantData(SUBDOMAIN))
        .isInstanceOf(RestClientException.class)
        .hasMessage("tenant service down");
  }

  // Same contract applies to the tenant-id overload used in enrichment paths.
  @Test
  void getRestrictedTenantData_tenantIdApiFailure_propagatesRestClientException() {
    tenantControllerApi.tenantIdException = new RestClientException("tenant service down");

    assertThatThrownBy(() -> tenantService.getRestrictedTenantData(TENANT_ID))
        .isInstanceOf(RestClientException.class)
        .hasMessage("tenant service down");
  }

  // Generated OpenAPI client rejects null subdomain before any HTTP call is made.
  @Test
  void getRestrictedTenantData_nullSubdomain_throwsHttpClientErrorException() {
    tenantService =
        new TenantService(
            new StubTenantServiceApiControllerFactory(new TenantControllerApi()),
            testCacheManager());

    assertThatThrownBy(() -> tenantService.getRestrictedTenantData((String) null))
        .isInstanceOf(HttpClientErrorException.class)
        .hasMessageContaining("subdomain");
  }

  // Generated OpenAPI client rejects null tenantId before any HTTP call is made.
  @Test
  void getRestrictedTenantData_nullTenantId_throwsHttpClientErrorException() {
    tenantService =
        new TenantService(
            new StubTenantServiceApiControllerFactory(new TenantControllerApi()),
            testCacheManager());

    assertThatThrownBy(() -> tenantService.getRestrictedTenantData((Long) null))
        .isInstanceOf(HttpClientErrorException.class)
        .hasMessageContaining("tenantId");
  }

  @Nested
  @SpringJUnitConfig(classes = TenantServiceTest.CacheTestConfig.class)
  class CachingBehavior {

    @Autowired private TenantService cachedTenantService;

    @Autowired private StubTenantControllerApi tenantControllerApi;

    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
      cacheManager.getCache(CacheManagerConfig.TENANT_CACHE).clear();
      tenantControllerApi.reset();
    }

    // Repeated subdomain lookups during request handling must not hammer tenant-service.
    @Test
    void getRestrictedTenantData_sameSubdomainTwice_callsApiOnce() {
      tenantControllerApi.subdomainResult =
          new RestrictedTenantDTO().id(TENANT_ID).subdomain(SUBDOMAIN);

      cachedTenantService.getRestrictedTenantData(SUBDOMAIN);
      cachedTenantService.getRestrictedTenantData(SUBDOMAIN);

      assertThat(tenantControllerApi.subdomainCalls.get()).isEqualTo(1);
    }

    // Tenant-id lookups are cached independently from subdomain lookups.
    @Test
    void getRestrictedTenantData_sameTenantIdTwice_callsApiOnce() {
      tenantControllerApi.tenantIdResult =
          new RestrictedTenantDTO().id(TENANT_ID).subdomain(SUBDOMAIN);

      cachedTenantService.getRestrictedTenantData(TENANT_ID);
      cachedTenantService.getRestrictedTenantData(TENANT_ID);

      assertThat(tenantControllerApi.tenantIdCalls.get()).isEqualTo(1);
    }

    @Test
    void getRestrictedTenantData_batchPopulatesIdAndSubdomainCacheEntries() {
      var tenant = new RestrictedTenantDTO().id(TENANT_ID).subdomain(SUBDOMAIN).name("Berlin");
      tenantControllerApi.tenantIdsResult = List.of(tenant);

      assertThat(cachedTenantService.getRestrictedTenantData(Set.of(TENANT_ID)))
          .containsExactly(tenant);

      assertThat(cachedTenantService.getRestrictedTenantData(TENANT_ID)).isSameAs(tenant);
      assertThat(cachedTenantService.getRestrictedTenantData(SUBDOMAIN)).isSameAs(tenant);
      assertThat(tenantControllerApi.tenantIdsCalls.get()).isEqualTo(1);
      assertThat(tenantControllerApi.tenantIdCalls.get()).isZero();
      assertThat(tenantControllerApi.subdomainCalls.get()).isZero();
    }

    @Test
    void getRestrictedTenantDataFresh_afterCachedLookup_returnsCurrentApiValue() {
      var disabled = new RestrictedTenantDTO().id(TENANT_ID).name("Disabled");
      var enabled = new RestrictedTenantDTO().id(TENANT_ID).name("Enabled");
      tenantControllerApi.tenantIdResult = disabled;

      assertThat(cachedTenantService.getRestrictedTenantData(TENANT_ID)).isSameAs(disabled);

      tenantControllerApi.tenantIdResult = enabled;

      assertThat(cachedTenantService.getRestrictedTenantDataFresh(TENANT_ID)).isSameAs(enabled);
      assertThat(tenantControllerApi.tenantIdCalls.get()).isEqualTo(2);
    }

    // Each subdomain is a distinct cache entry for multitenancy routing.
    @Test
    void getRestrictedTenantData_differentSubdomains_callsApiForEach() {
      cachedTenantService.getRestrictedTenantData("alpha");
      cachedTenantService.getRestrictedTenantData("beta");

      assertThat(tenantControllerApi.subdomainCalls.get()).isEqualTo(2);
    }

    // Numeric tenant ids are cached separately per id value.
    @Test
    void getRestrictedTenantData_differentTenantIds_callsApiForEach() {
      cachedTenantService.getRestrictedTenantData(1L);
      cachedTenantService.getRestrictedTenantData(2L);

      assertThat(tenantControllerApi.tenantIdCalls.get()).isEqualTo(2);
    }

    // Concurrent warm-up should not fan out more than one tenant-service call per key.
    @Test
    void getRestrictedTenantData_concurrentSameSubdomain_callsApiAtMostOnce() throws Exception {
      tenantControllerApi.subdomainResult =
          new RestrictedTenantDTO().id(TENANT_ID).subdomain(SUBDOMAIN);
      CountDownLatch threadsReady = new CountDownLatch(2);
      CountDownLatch releaseThreads = new CountDownLatch(1);
      tenantControllerApi.subdomainLatch = new CountDownLatch[] {threadsReady, releaseThreads};

      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        for (int i = 0; i < 2; i++) {
          executor.submit(() -> cachedTenantService.getRestrictedTenantData(SUBDOMAIN));
        }
        threadsReady.await(5, TimeUnit.SECONDS);
        releaseThreads.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        executor.shutdownNow();
      }

      // Spring @Cacheable does not enable sync=true; under race both threads may miss once.
      assertThat(tenantControllerApi.subdomainCalls.get()).isLessThanOrEqualTo(2);
    }
  }

  @Configuration
  @EnableCaching
  static class CacheTestConfig {

    @Bean
    StubTenantControllerApi stubTenantControllerApi() {
      return new StubTenantControllerApi();
    }

    @Bean
    TenantService tenantService(
        StubTenantControllerApi stubTenantControllerApi, CacheManager cacheManager) {
      return new TenantService(
          new StubTenantServiceApiControllerFactory(stubTenantControllerApi), cacheManager);
    }

    @Bean
    CacheManager cacheManager() {
      var cacheManager = new SimpleCacheManager();
      cacheManager.setCaches(List.of(new ConcurrentMapCache(CacheManagerConfig.TENANT_CACHE)));
      cacheManager.initializeCaches();
      return cacheManager;
    }
  }

  static final class StubTenantControllerApi extends TenantControllerApi {

    RestrictedTenantDTO subdomainResult;
    RestrictedTenantDTO tenantIdResult;
    List<RestrictedTenantDTO> tenantIdsResult;
    List<Long> lastTenantIds;
    final List<List<Long>> tenantIdRequests = new ArrayList<>();
    RuntimeException subdomainException;
    RuntimeException tenantIdException;
    final AtomicInteger subdomainCalls = new AtomicInteger();
    final AtomicInteger tenantIdCalls = new AtomicInteger();
    final AtomicInteger tenantIdsCalls = new AtomicInteger();
    CountDownLatch[] subdomainLatch;

    void reset() {
      subdomainResult = null;
      tenantIdResult = null;
      tenantIdsResult = null;
      lastTenantIds = null;
      subdomainException = null;
      tenantIdException = null;
      subdomainCalls.set(0);
      tenantIdCalls.set(0);
      tenantIdsCalls.set(0);
      tenantIdRequests.clear();
      subdomainLatch = null;
    }

    @Override
    public RestrictedTenantDTO getRestrictedTenantDataBySubdomain(String subdomain, Long tenantId) {
      subdomainCalls.incrementAndGet();
      awaitLatch();
      if (subdomainException != null) {
        throw subdomainException;
      }
      return subdomainResult != null
          ? subdomainResult
          : new RestrictedTenantDTO().id(1L).subdomain(subdomain);
    }

    @Override
    public RestrictedTenantDTO getRestrictedTenantDataByTenantId(Long tenantId) {
      tenantIdCalls.incrementAndGet();
      if (tenantIdException != null) {
        throw tenantIdException;
      }
      return tenantIdResult != null ? tenantIdResult : new RestrictedTenantDTO().id(tenantId);
    }

    @Override
    public List<RestrictedTenantDTO> getRestrictedTenantDataByTenantIds(List<Long> tenantIds) {
      tenantIdsCalls.incrementAndGet();
      lastTenantIds = List.copyOf(tenantIds);
      tenantIdRequests.add(lastTenantIds);
      return tenantIdsResult != null ? tenantIdsResult : List.of();
    }

    private void awaitLatch() {
      if (subdomainLatch == null) {
        return;
      }
      try {
        subdomainLatch[0].countDown();
        subdomainLatch[1].await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
  }

  static final class StubTenantServiceApiControllerFactory
      extends TenantServiceApiControllerFactory {

    private final TenantControllerApi api;

    StubTenantServiceApiControllerFactory(TenantControllerApi api) {
      this.api = api;
    }

    @Override
    public TenantControllerApi createControllerApi() {
      return api;
    }
  }

  private static CacheManager testCacheManager() {
    var cacheManager = new SimpleCacheManager();
    cacheManager.setCaches(List.of(new ConcurrentMapCache(CacheManagerConfig.TENANT_CACHE)));
    cacheManager.initializeCaches();
    return cacheManager;
  }
}
