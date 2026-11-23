package de.caritas.cob.userservice.api.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiClient;
import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.tenantservice.generated.web.TenantControllerApi;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class ChatRecoverySingleTenantHttpTest {
  @Test
  void nullTenantResolvesPolicyThroughSupportedSingleTenantHttpContractAndFailsClosed() {
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    var controller =
        new TenantControllerApi(
            new TenantServiceApiClient(rest).setBasePath("https://tenant-service.test"));
    var factory = mock(TenantServiceApiControllerFactory.class);
    when(factory.createControllerApi()).thenReturn(controller);
    var service =
        new ChatRecoveryEnrollmentPolicyService(
            new TenantService(factory, new ConcurrentMapCacheManager()),
            mock(UserRepository.class),
            mock(ConsultantRepository.class));
    ReflectionTestUtils.setField(service, "multitenancyEnabled", false);
    server
        .expect(requestTo("https://tenant-service.test/tenant/public/single"))
        .andRespond(
            withSuccess(
                """
          {"id":42,"settings":{"tenantAdminControls":{"chatRecoverySettings":{"asker":"RECOVERY_KEY","consultant":"LOGIN_PASSWORD","revision":7}}}}
          """,
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://tenant-service.test/tenant/public/single"))
        .andRespond(withServerError());
    assertEquals(
        new ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot("LOGIN_PASSWORD", 7),
        service.forNewConsultant(null));
    var error =
        assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException.class,
            () -> service.forNewConsultant(null));
    assertEquals(org.springframework.http.HttpStatus.BAD_GATEWAY, error.getHttpStatus());
    server.verify();
  }
}
