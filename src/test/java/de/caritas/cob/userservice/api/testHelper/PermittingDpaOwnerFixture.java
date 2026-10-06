package de.caritas.cob.userservice.api.testHelper;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.chat.GroupCounsellingDpaPolicy;
import de.caritas.cob.userservice.api.service.dpa.NewCounsellingDpaPolicy;
import de.caritas.cob.userservice.api.service.dpa.TenantDpaGateReadClient;
import de.caritas.cob.userservice.api.service.httpheader.HttpHeadersResolver;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.api.service.matrixgroup.GroupMatrixPolicySettings;
import de.caritas.cob.userservice.api.service.matrixgroup.MatrixGroupParticipationHistory;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Mechanical compatibility for older facade tests. The policy and generated HTTP clients are real;
 * only the external identity provider and owner/agency HTTP responses are fixtures. Public HTTP
 * journeys in IndividualCounsellingDpaGateIT remain the authority for counselling access.
 */
public final class PermittingDpaOwnerFixture {
  private PermittingDpaOwnerFixture() {}

  /** Real deployed-legacy group policy; the dormant history adapter performs no HTTP reads. */
  public static GroupCounsellingDpaPolicy groupPolicy() {
    var ownerPolicy = policy();
    var agencies = (AgencyService) ReflectionTestUtils.getField(ownerPolicy, "agencyService");
    return new GroupCounsellingDpaPolicy(
        ownerPolicy,
        mock(de.caritas.cob.userservice.api.port.out.ChatAgencyRepository.class),
        agencies,
        new MatrixGroupParticipationHistory(
            new GroupMatrixPolicySettings(false, "", ""),
            new RestTemplate(),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            null));
  }

  public static NewCounsellingDpaPolicy policy() {
    var transport = new RestTemplate();
    var server = MockRestServiceServer.bindTo(transport).build();
    server
        .expect(ExpectedCount.manyTimes(), anything())
        .andRespond(
            request -> {
              String path = request.getURI().getPath();
              if (path.endsWith("/dpa/gate")) {
                return withSuccess(
                        "{\"dpaPublished\":true,\"dpaSigned\":true}", MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.equals("/tenant/public/single")) {
                return withSuccess("{\"id\":41}", MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.startsWith("/agencies/")) {
                return withSuccess(
                        "[{\"id\":1,\"tenantId\":41,\"consultingType\":1}]",
                        MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              throw new AssertionError("Unexpected DPA fixture operation: " + path);
            });
    var factory = new TenantServiceApiControllerFactory();
    ReflectionTestUtils.setField(factory, "restTemplate", transport);
    ReflectionTestUtils.setField(factory, "tenantServiceApiUrl", "http://synthetic-owner.test");
    var agencyFactory = new AgencyServiceApiControllerFactory();
    ReflectionTestUtils.setField(agencyFactory, "restTemplate", transport);
    ReflectionTestUtils.setField(
        agencyFactory, "agencyServiceApiUrl", "http://synthetic-agency.test");
    var headers = new SecurityHeaderSupplier(new AuthenticatedUser());
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "X-CSRF-Token");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "CSRF-TOKEN");
    var identity = mock(IdentityAuthentication.class);
    lenient()
        .when(identity.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("synthetic-service-token", 60, 60, "synthetic-refresh"));
    var config = mock(IdentityClientConfig.class);
    var serviceUser = new TechnicalUserConfig();
    serviceUser.setUsername("synthetic-service");
    serviceUser.setPassword("synthetic-fixture");
    lenient().when(config.getTechnicalUser()).thenReturn(serviceUser);
    var caches = new ConcurrentMapCacheManager("agencyCache", "tenantCache");
    var agencies =
        new AgencyService(
            headers, new TenantHeaderSupplier(new HttpHeadersResolver()), agencyFactory, caches);
    return new NewCounsellingDpaPolicy(
        new TenantDpaGateReadClient(factory, identity, config, headers),
        agencies,
        new TenantService(factory, caches));
  }
}
