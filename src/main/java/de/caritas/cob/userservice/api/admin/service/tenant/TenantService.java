package de.caritas.cob.userservice.api.admin.service.tenant;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.config.CacheManagerConfig;
import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantService {

  private static final int MAX_TENANT_IDS_PER_BATCH = 100;

  private final @NonNull TenantServiceApiControllerFactory tenantServiceApiControllerFactory;
  private final @NonNull CacheManager cacheManager;

  @Cacheable(cacheNames = CacheManagerConfig.TENANT_CACHE, key = "#subdomain")
  public RestrictedTenantDTO getRestrictedTenantData(String subdomain) {
    log.info("Calling tenant service to get tenant data for subdomain {}", subdomain);
    return tenantServiceApiControllerFactory
        .createControllerApi()
        .getRestrictedTenantDataBySubdomain(subdomain, null);
  }

  @Cacheable(cacheNames = CacheManagerConfig.TENANT_CACHE, key = "#tenantId")
  public RestrictedTenantDTO getRestrictedTenantData(Long tenantId) {
    requireConcreteTenantId(tenantId);
    log.info("Calling tenant service to get tenant data for tenantId {}", tenantId);

    return tenantServiceApiControllerFactory
        .createControllerApi()
        .getRestrictedTenantDataByTenantId(tenantId);
  }

  public RestrictedTenantDTO getRestrictedTenantDataFresh(Long tenantId) {
    requireConcreteTenantId(tenantId);
    log.info("Calling tenant service for current tenant data for tenantId {}", tenantId);
    return tenantServiceApiControllerFactory
        .createControllerApi()
        .getRestrictedTenantDataByTenantId(tenantId);
  }

  /** Explicit platform-branding lookup; generic tenant operations still reject technical id 0. */
  @Cacheable(cacheNames = CacheManagerConfig.TENANT_CACHE, key = "'platform-branding'")
  public RestrictedTenantDTO getPlatformTenantData() {
    log.info("Calling tenant service to get platform branding data");
    return tenantServiceApiControllerFactory
        .createControllerApi()
        .getRestrictedTenantDataByTenantId(TenantContext.TECHNICAL_TENANT_ID);
  }

  public List<RestrictedTenantDTO> getRestrictedTenantData(Set<Long> tenantIds) {
    var concreteTenantIds =
        tenantIds.stream()
            .filter(Objects::nonNull)
            .filter(tenantId -> !TenantContext.TECHNICAL_TENANT_ID.equals(tenantId))
            .toList();
    if (concreteTenantIds.isEmpty()) {
      return List.of();
    }

    log.info(
        "Calling tenant service to get tenant data for {} tenant ids", concreteTenantIds.size());
    var tenantControllerApi = tenantServiceApiControllerFactory.createControllerApi();
    var tenants = new ArrayList<RestrictedTenantDTO>();
    for (var tenantIdBatch : Lists.partition(concreteTenantIds, MAX_TENANT_IDS_PER_BATCH)) {
      var batchResult = tenantControllerApi.getRestrictedTenantDataByTenantIds(tenantIdBatch);
      if (batchResult != null) {
        tenants.addAll(batchResult);
      }
    }

    var tenantCache =
        Objects.requireNonNull(
            cacheManager.getCache(CacheManagerConfig.TENANT_CACHE),
            "Tenant cache must be configured");
    tenants.stream()
        .filter(Objects::nonNull)
        .forEach(
            tenant -> {
              if (tenant.getId() != null) {
                tenantCache.put(tenant.getId(), tenant);
              }
              if (tenant.getSubdomain() != null) {
                tenantCache.put(tenant.getSubdomain(), tenant);
              }
            });
    return tenants;
  }

  private void requireConcreteTenantId(Long tenantId) {
    if (TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) {
      throw new IllegalArgumentException("Concrete tenant id required");
    }
  }
}
