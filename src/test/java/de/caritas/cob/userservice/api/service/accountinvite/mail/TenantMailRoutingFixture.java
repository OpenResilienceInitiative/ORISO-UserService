package de.caritas.cob.userservice.api.service.accountinvite.mail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailClient;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService.Mode;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService.Route;

/** Routing for tests about mail content: every tenant uses the platform server. */
public final class TenantMailRoutingFixture {

  private TenantMailRoutingFixture() {}

  public static TenantSystemEmailRouteService platformRoutes() {
    TenantSystemEmailRouteService routes = mock(TenantSystemEmailRouteService.class);
    when(routes.resolveTransport(any())).thenReturn(new Route(Mode.PLATFORM, null));
    return routes;
  }

  public static TenantSystemEmailClient unusedRelay() {
    return mock(TenantSystemEmailClient.class);
  }
}
