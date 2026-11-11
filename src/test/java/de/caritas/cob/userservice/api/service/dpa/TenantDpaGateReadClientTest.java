package de.caritas.cob.userservice.api.service.dpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TenantDpaGateReadClientTest {

  @Test
  void readsOptionalSignatureTimeFromTheExistingAuthorizedGate() {
    var transport = new RestTemplate();
    var owner = MockRestServiceServer.bindTo(transport).build();
    var factory = new TenantServiceApiControllerFactory();
    ReflectionTestUtils.setField(factory, "restTemplate", transport);
    ReflectionTestUtils.setField(factory, "tenantServiceApiUrl", "http://synthetic-owner.test");
    var config = mock(IdentityClientConfig.class);
    when(config.isFiveActorMode()).thenReturn(true);
    when(config.taskAccessToken(TaskPurpose.RUNTIME_POLICY)).thenReturn("synthetic-task-token");
    var headers = mock(SecurityHeaderSupplier.class);
    var authorization = new HttpHeaders();
    authorization.setBearerAuth("synthetic-task-token");
    when(headers.getKeycloakAndCsrfHttpHeaders("synthetic-task-token")).thenReturn(authorization);
    var client =
        new TenantDpaGateReadClient(factory, mock(IdentityAuthentication.class), config, headers);

    owner
        .expect(requestTo("http://synthetic-owner.test/tenantadmin/2/dpa/gate"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer synthetic-task-token"))
        .andExpect(header("tenantId", "0"))
        .andRespond(
            withSuccess(
                """
                {
                  "dpaPublished": true,
                  "dpaSigned": true,
                  "dpaStatus": "VALID",
                  "currentDpaVersion": "2026-08-01T08:00:00",
                  "dpaSignedAt": "2026-08-03T10:11:12Z",
                  "renewalGraceActive": false,
                  "newCounsellingAllowed": true
                }
                """,
                MediaType.APPLICATION_JSON));
    owner
        .expect(requestTo("http://synthetic-owner.test/tenantadmin/3/dpa/gate"))
        .andRespond(
            withSuccess(
                """
                {"dpaPublished":true,"dpaSigned":true}
                """,
                MediaType.APPLICATION_JSON));

    owner
        .expect(requestTo("http://synthetic-owner.test/tenantadmin/4/dpa/gate"))
        .andRespond(
            withSuccess(
                """
                {"dpaPublished":true,"dpaSigned":true,"dpaSignedAt":1785751872}
                """,
                MediaType.APPLICATION_JSON));
    owner
        .expect(requestTo("http://synthetic-owner.test/tenantadmin/5/dpa/gate"))
        .andRespond(
            withSuccess(
                """
                {"dpaPublished":true,"dpaSigned":true,"dpaSignedAt":null}
                """,
                MediaType.APPLICATION_JSON));

    assertEquals("2026-08-03T10:11:12Z", client.read(2L).getDpaSignedAt());
    assertNull(client.read(3L).getDpaSignedAt());
    assertThrows(IllegalStateException.class, () -> client.read(4L));
    assertNull(client.read(5L).getDpaSignedAt());
    owner.verify();
  }
}
