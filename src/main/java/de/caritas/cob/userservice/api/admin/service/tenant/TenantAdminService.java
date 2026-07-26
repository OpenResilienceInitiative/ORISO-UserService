package de.caritas.cob.userservice.api.admin.service.tenant;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.cache.SharedReadCache;
import de.caritas.cob.userservice.api.service.cache.SharedReadCache.CacheName;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.ApiClient;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantAdminControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
@RequiredArgsConstructor
public class TenantAdminService {

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull TenantAdminServiceApiControllerFactory
      tenantAdminServiceApiControllerFactory;
  private final @NonNull SharedReadCache sharedReadCache;

  public TenantDTO getTenantById(Long tenantId) throws RestClientException {
    if (tenantId == null) {
      return loadTenantById(null);
    }
    return sharedReadCache.getOrLoad(
        CacheName.TENANT_ADMIN, "id:" + tenantId, TenantDTO.class, () -> loadTenantById(tenantId));
  }

  private TenantDTO loadTenantById(Long tenantId) {
    TenantAdminControllerApi controllerApi =
        this.tenantAdminServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(controllerApi.getApiClient());
    return controllerApi.getTenantById(tenantId);
  }

  private void addDefaultHeaders(ApiClient apiClient) {
    HttpHeaders headers = this.securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders();
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
