package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.tenantservice.generated.ApiClient;
import de.caritas.cob.userservice.tenantservice.generated.web.TenantControllerApi;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class AskerIdentityPolicySingleTenantTest {
  private final AnnotationConfigApplicationContext context =
      new AnnotationConfigApplicationContext();
  private final RestTemplate transport = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(transport).build();

  @AfterEach
  void close() {
    context.close();
  }

  @Test
  void singleTenantLegacyIdentityUsesFreshConfiguredPoliciesForBothAccountChoices() {
    var tenants = configured(false);
    singleResponse(true);
    assertThat(AskerIdentityPolicy.customNamesAllowed(tenants, null)).isTrue();
    server.verify();
    server.reset();
    singleResponse(false);
    assertThat(AskerIdentityPolicy.customNamesAllowed(tenants, null)).isFalse();
    server.verify();
    server.reset();
    singleResponse(true);
    AskerIdentityPolicy.requireEmailCollectionAllowed(tenants, null);
    server.verify();
    server.reset();
    singleResponse(false);
    assertThatThrownBy(() -> AskerIdentityPolicy.requireEmailCollectionAllowed(tenants, null))
        .isInstanceOf(ForbiddenException.class);
    server.verify();
  }

  @Test
  void multitenantMissingIdentityNeverFallsBackToConfiguredTenant() {
    var tenants = configured(true);
    assertThatThrownBy(() -> AskerIdentityPolicy.emailCollectionAllowed(tenants, null))
        .isInstanceOf(ServiceUnavailableException.class);
    server.verify();
  }

  @Test
  void technicalOrNegativeIdentityNeverFallsBackEvenInSingleTenantMode() {
    var tenants = configured(false);
    for (long id : new long[] {0, -1}) {
      assertThatThrownBy(() -> AskerIdentityPolicy.emailCollectionAllowed(tenants, id))
          .isInstanceOf(ServiceUnavailableException.class);
    }
    server.verify();
  }

  @Test
  void concreteIdentityMustMatchEvenInSingleTenantMode() {
    var tenants = configured(false);
    server
        .expect(requestTo("https://tenant.example/tenant/public/id/42"))
        .andRespond(
            withSuccess(
                "{\"id\":99,\"settings\":{\"featureAskerEmailEnabled\":true}}",
                MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> AskerIdentityPolicy.emailCollectionAllowed(tenants, 42L))
        .isInstanceOf(ServiceUnavailableException.class);
    server.verify();
  }

  @Test
  void configuredProjectionRequiresPositiveIdentityAndSettings() {
    var tenants = configured(false);
    for (String body :
        new String[] {
          "{}", "{\"id\":0,\"settings\":{}}", "{\"id\":-1,\"settings\":{}}", "{\"id\":42}"
        }) {
      server
          .expect(requestTo("https://tenant.example/tenant/public/single"))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
      assertThatThrownBy(() -> AskerIdentityPolicy.emailCollectionAllowed(tenants, null))
          .isInstanceOf(ServiceUnavailableException.class);
      server.verify();
      server.reset();
    }
  }

  private TenantService configured(boolean multitenant) {
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test", Map.of("multitenancy.enabled", Boolean.toString(multitenant))));
    var factory =
        new TenantServiceApiControllerFactory() {
          @Override
          public TenantControllerApi createControllerApi() {
            return new TenantControllerApi(
                new ApiClient(transport).setBasePath("https://tenant.example"));
          }
        };
    context.registerBean(
        TenantService.class, () -> new TenantService(factory, new ConcurrentMapCacheManager()));
    context.refresh();
    return context.getBean(TenantService.class);
  }

  private void singleResponse(boolean enabled) {
    server
        .expect(requestTo("https://tenant.example/tenant/public/single"))
        .andRespond(
            withSuccess(
                "{\"id\":42,\"settings\":{\"featureDisplayNameEditable\":"
                    + enabled
                    + ",\"featureAskerEmailEnabled\":"
                    + enabled
                    + "}}",
                MediaType.APPLICATION_JSON));
  }
}
