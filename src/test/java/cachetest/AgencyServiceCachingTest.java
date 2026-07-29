package cachetest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyserivce.generated.web.AgencyControllerApi;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyResponseDTO;
import de.caritas.cob.userservice.api.config.CacheManagerConfig;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestTemplate;

@SpringJUnitConfig(classes = AgencyServiceCachingTest.CacheTestConfig.class)
@TestPropertySource(
    properties = {
      "multitenancy.enabled=true",
      "agency.service.api.url=http://localhost",
      "csrf.header.property=X-CSRF",
      "csrf.cookie.property=CSRF"
    })
class AgencyServiceCachingTest {

  @Autowired private AgencyService agencyService;
  @Autowired private AgencyControllerApi agencyControllerApi;
  @Autowired private ApiClient apiClient;
  @Autowired private SecurityHeaderSupplier securityHeaderSupplier;
  @Autowired private CacheManager cacheManager;

  @BeforeEach
  void setUp() {
    cacheManager.getCache(CacheManagerConfig.AGENCY_CACHE).clear();
    reset(agencyControllerApi, apiClient, securityHeaderSupplier);
    when(agencyControllerApi.getApiClient()).thenReturn(apiClient);
    when(securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders())
        .thenReturn(new HttpHeaders());
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void batchLookup_WarmsTenantScopedSingleAgencyCacheWithoutNegativeCaching() {
    TenantContext.setCurrentTenant(7L);
    var agencyResponse = new AgencyResponseDTO().id(42L).name("Agency 42");
    when(agencyControllerApi.getAgenciesByIds(List.of(42L, 43L)))
        .thenReturn(List.of(agencyResponse));

    var batchResult = agencyService.getAgencies(List.of(42L, 43L));
    var cachedSingleResult = agencyService.getAgency(42L);

    assertThat(batchResult).singleElement().isSameAs(cachedSingleResult);
    assertThat(cacheManager.getCache(CacheManagerConfig.AGENCY_CACHE).get("tenant:7:id:43"))
        .isNull();
    verify(agencyControllerApi, times(1)).getAgenciesByIds(List.of(42L, 43L));
  }

  @Test
  void singleAgencyCache_DoesNotLeakAcrossTenantScopes() {
    TenantContext.setCurrentTenant(7L);
    when(agencyControllerApi.getAgenciesByIds(List.of(42L)))
        .thenReturn(List.of(new AgencyResponseDTO().id(42L).name("Tenant 7")));
    assertThat(agencyService.getAgency(42L).getName()).isEqualTo("Tenant 7");

    TenantContext.setCurrentTenant(8L);
    when(agencyControllerApi.getAgenciesByIds(List.of(42L)))
        .thenReturn(List.of(new AgencyResponseDTO().id(42L).name("Tenant 8")));
    assertThat(agencyService.getAgency(42L).getName()).isEqualTo("Tenant 8");

    verify(agencyControllerApi, times(2)).getAgenciesByIds(List.of(42L));
  }

  @TestConfiguration
  @EnableCaching
  static class CacheTestConfig {

    @Bean
    AgencyControllerApi agencyControllerApi() {
      return mock(AgencyControllerApi.class);
    }

    @Bean
    ApiClient apiClient() {
      return mock(ApiClient.class);
    }

    @Bean
    RestTemplate restTemplate() {
      return new RestTemplate();
    }

    @Bean
    SecurityHeaderSupplier securityHeaderSupplier() {
      return mock(SecurityHeaderSupplier.class);
    }

    @Bean
    TenantHeaderSupplier tenantHeaderSupplier() {
      return mock(TenantHeaderSupplier.class);
    }

    @Bean
    AgencyServiceApiControllerFactory agencyServiceApiControllerFactory(
        AgencyControllerApi agencyControllerApi) {
      var factory = mock(AgencyServiceApiControllerFactory.class);
      when(factory.createControllerApi()).thenReturn(agencyControllerApi);
      return factory;
    }

    @Bean
    CacheManager cacheManager() {
      var cacheManager = new SimpleCacheManager();
      cacheManager.setCaches(List.of(new ConcurrentMapCache(CacheManagerConfig.AGENCY_CACHE)));
      cacheManager.initializeCaches();
      return cacheManager;
    }

    @Bean
    AgencyService agencyService(
        SecurityHeaderSupplier securityHeaderSupplier,
        TenantHeaderSupplier tenantHeaderSupplier,
        AgencyServiceApiControllerFactory agencyServiceApiControllerFactory,
        CacheManager cacheManager) {
      return new AgencyService(
          securityHeaderSupplier,
          tenantHeaderSupplier,
          agencyServiceApiControllerFactory,
          cacheManager);
    }
  }
}
