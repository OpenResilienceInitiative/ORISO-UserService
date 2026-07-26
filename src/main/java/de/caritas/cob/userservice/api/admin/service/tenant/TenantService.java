package de.caritas.cob.userservice.api.admin.service.tenant;

import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.cache.SharedReadCache;
import de.caritas.cob.userservice.api.service.cache.SharedReadCache.CacheName;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantService {

  private final @NonNull TenantServiceApiControllerFactory tenantServiceApiControllerFactory;
  private final @NonNull SharedReadCache sharedReadCache;

  public RestrictedTenantDTO getRestrictedTenantData(String subdomain) {
    if (subdomain == null) {
      return loadBySubdomain(null);
    }
    return sharedReadCache.getOrLoad(
        CacheName.TENANT,
        subdomainKey(subdomain),
        RestrictedTenantDTO.class,
        () -> loadBySubdomain(subdomain));
  }

  private RestrictedTenantDTO loadBySubdomain(String subdomain) {
    log.info("Calling tenant service to get tenant data for subdomain {}", subdomain);
    RestrictedTenantDTO tenant =
        tenantServiceApiControllerFactory
            .createControllerApi()
            .getRestrictedTenantDataBySubdomain(subdomain);
    if (tenant != null && tenant.getId() != null) {
      sharedReadCache.put(CacheName.TENANT, tenantIdKey(tenant.getId()), tenant);
    }
    return tenant;
  }

  public RestrictedTenantDTO getRestrictedTenantData(Long tenantId) {
    if (tenantId == null) {
      return loadByTenantId(null);
    }
    return sharedReadCache.getOrLoad(
        CacheName.TENANT,
        tenantIdKey(tenantId),
        RestrictedTenantDTO.class,
        () -> loadByTenantId(tenantId));
  }

  private RestrictedTenantDTO loadByTenantId(Long tenantId) {
    log.info("Calling tenant service to get tenant data for tenantId {}", tenantId);
    RestrictedTenantDTO tenant =
        tenantServiceApiControllerFactory
            .createControllerApi()
            .getRestrictedTenantDataByTenantId(tenantId);
    if (tenant != null && tenant.getSubdomain() != null) {
      sharedReadCache.put(CacheName.TENANT, subdomainKey(tenant.getSubdomain()), tenant);
    }
    return tenant;
  }

  public RestrictedTenantDTO getRestrictedTenantDataFresh(Long tenantId) {
    RestrictedTenantDTO tenant = loadByTenantId(tenantId);
    if (tenant != null) {
      sharedReadCache.put(CacheName.TENANT, tenantIdKey(tenantId), tenant);
    }
    return tenant;
  }

  private String subdomainKey(String subdomain) {
    return "subdomain:" + subdomain;
  }

  private String tenantIdKey(Long tenantId) {
    return "id:" + tenantId;
  }
}
