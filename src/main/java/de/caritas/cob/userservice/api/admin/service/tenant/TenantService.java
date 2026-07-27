package de.caritas.cob.userservice.api.admin.service.tenant;

import de.caritas.cob.userservice.api.config.CacheManagerConfig;
import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
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
    log.info("Calling tenant service to get tenant data for tenantId {}", tenantId);

    return tenantServiceApiControllerFactory
        .createControllerApi()
        .getRestrictedTenantDataByTenantId(tenantId);
  }

  public RestrictedTenantDTO getRestrictedTenantDataFresh(Long tenantId) {
    log.info("Calling tenant service for current tenant data for tenantId {}", tenantId);
    return tenantServiceApiControllerFactory
        .createControllerApi()
        .getRestrictedTenantDataByTenantId(tenantId);
  }

  public List<RestrictedTenantDTO> getRestrictedTenantData(Set<Long> tenantIds) {
    if (tenantIds.isEmpty()) {
      return List.of();
    }

    log.info("Calling tenant service to get tenant data for {} tenant ids", tenantIds.size());
    var tenants =
        tenantServiceApiControllerFactory
            .createControllerApi()
            .getRestrictedTenantDataByTenantIds(List.copyOf(tenantIds));
    if (tenants == null) {
      return List.of();
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
}
