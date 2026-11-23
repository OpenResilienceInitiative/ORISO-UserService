package de.caritas.cob.userservice.api.testHelper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.tenantservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

/** Real operational admission; only the remote owning TenantService API is replaced. */
public final class PausedTenantAuthorityFixture {
  private PausedTenantAuthorityFixture() {}

  public static TenantService paused(long tenantId) {
    var api = mock(TenantControllerApi.class);
    when(api.getRestrictedTenantDataByTenantId(tenantId))
        .thenReturn(new RestrictedTenantDTO().id(tenantId).active(false));
    var factory = mock(TenantServiceApiControllerFactory.class);
    when(factory.createControllerApi()).thenReturn(api);
    return new TenantService(factory, new ConcurrentMapCacheManager());
  }
}
