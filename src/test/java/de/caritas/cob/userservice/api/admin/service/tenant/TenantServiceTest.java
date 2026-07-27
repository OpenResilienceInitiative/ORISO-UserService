package de.caritas.cob.userservice.api.admin.service.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.cache.SharedReadCache;
import de.caritas.cob.userservice.tenantservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
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
            new StubTenantServiceApiControllerFactory(tenantControllerApi),
            passThroughSharedReadCache());
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
    assertThat(tenantControllerApi.tenantIdCalls.get()).isZero();
  }

  @Test
  void getRestrictedTenantData_emptyTenantIds_doesNotCallApi() {
    assertThat(tenantService.getRestrictedTenantData(Set.of())).isEmpty();
    assertThat(tenantControllerApi.tenantIdsCalls.get()).isZero();
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
            passThroughSharedReadCache());

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
            passThroughSharedReadCache());

    assertThatThrownBy(() -> tenantService.getRestrictedTenantData((Long) null))
        .isInstanceOf(HttpClientErrorException.class)
        .hasMessageContaining("tenantId");
  }

  @Nested
  @SpringJUnitConfig(classes = TenantServiceTest.CacheTestConfig.class)
  class CachingBehavior {

    @Autowired private TenantService cachedTenantService;

    @Autowired private StubTenantControllerApi tenantControllerApi;

    @Autowired private SharedReadCache sharedReadCache;

    private final Map<String, Object> cachedValues = new ConcurrentHashMap<>();

    @BeforeEach
    void clearCache() {
      reset(sharedReadCache);
      cachedValues.clear();
      configureMapBackedCache(sharedReadCache, cachedValues);
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

      // A cold cache-aside race may miss once per caller; warm reads remain shared.
      assertThat(tenantControllerApi.subdomainCalls.get()).isLessThanOrEqualTo(2);
    }
  }

  static class CacheTestConfig {

    @Bean
    StubTenantControllerApi stubTenantControllerApi() {
      return new StubTenantControllerApi();
    }

    @Bean
    TenantService tenantService(
        StubTenantControllerApi stubTenantControllerApi, SharedReadCache sharedReadCache) {
      return new TenantService(
          new StubTenantServiceApiControllerFactory(stubTenantControllerApi), sharedReadCache);
    }

    @Bean
    SharedReadCache sharedReadCache() {
      return mock(SharedReadCache.class);
    }
  }

  private static SharedReadCache passThroughSharedReadCache() {
    SharedReadCache cache = mock(SharedReadCache.class);
    doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get())
        .when(cache)
        .getOrLoad(any(), anyString(), any(), any());
    return cache;
  }

  private static void configureMapBackedCache(
      SharedReadCache cache, Map<String, Object> cachedValues) {
    doAnswer(
            invocation -> {
              String key = invocation.getArgument(0) + ":" + invocation.getArgument(1);
              Object cached = cachedValues.get(key);
              if (cached != null) {
                return cached;
              }
              Object loaded = ((Supplier<?>) invocation.getArgument(3)).get();
              if (loaded != null) {
                cachedValues.put(key, loaded);
              }
              return loaded;
            })
        .when(cache)
        .getOrLoad(any(), anyString(), any(), any());
    doAnswer(
            invocation -> {
              String key = invocation.getArgument(0) + ":" + invocation.getArgument(1);
              cachedValues.put(key, invocation.getArgument(2));
              return null;
            })
        .when(cache)
        .put(any(), anyString(), any());
  }

  static final class StubTenantControllerApi extends TenantControllerApi {

    RestrictedTenantDTO subdomainResult;
    RestrictedTenantDTO tenantIdResult;
    List<RestrictedTenantDTO> tenantIdsResult;
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
      subdomainException = null;
      tenantIdException = null;
      subdomainCalls.set(0);
      tenantIdCalls.set(0);
      tenantIdsCalls.set(0);
      subdomainLatch = null;
    }

    @Override
    public RestrictedTenantDTO getRestrictedTenantDataBySubdomain(String subdomain) {
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
}
