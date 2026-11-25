package de.caritas.cob.userservice.api.service.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** The RUNTIME actor finishes Träger deletions in TenantService (ORISO-Admin#1525). */
class TenantDeletionFinishClientTest {

  private static final String TENANT_SERVICE_URL = "https://tenant.example/service/";

  private MockRestServiceServer server;
  private IdentityClientConfig identity;
  private TenantDeletionFinishClient client;

  @BeforeEach
  void setUp() {
    var restTemplate = new RestTemplate();
    server = MockRestServiceServer.bindTo(restTemplate).build();
    identity = mock(IdentityClientConfig.class);
    var headers = new SecurityHeaderSupplier(new AuthenticatedUser());
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "csrfHeader");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "csrfCookie");
    client = new TenantDeletionFinishClient(restTemplate, headers, identity);
    ReflectionTestUtils.setField(client, "tenantServiceApiUrl", TENANT_SERVICE_URL);
    when(identity.isFiveActorMode()).thenReturn(true);
    when(identity.taskAccessToken(TaskPurpose.TENANT_DELETION)).thenReturn("runtime-token");
  }

  @Test
  void listsTheTraegerInDeletionWithTheRuntimeTokenForThePurpose() {
    server
        .expect(requestTo("https://tenant.example/service/tenant/deletions"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer runtime-token"))
        .andRespond(withSuccess("[121, 7]", MediaType.APPLICATION_JSON));

    assertThat(client.findTenantsMarkedForDeletion()).containsExactly(121L, 7L);
    server.verify();
  }

  @Test
  void removesATraegerAndCountsOneAlreadyGoneAsRemoved() {
    server
        .expect(requestTo("https://tenant.example/service/tenant/121/deletion"))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer runtime-token"))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));
    server
        .expect(requestTo("https://tenant.example/service/tenant/122/deletion"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    client.removeTenant(121L);
    client.removeTenant(122L);
    server.verify();
  }

  @Test
  void refusesToTreatARefusalAsRemoved() {
    server
        .expect(requestTo("https://tenant.example/service/tenant/121/deletion"))
        .andRespond(withStatus(HttpStatus.CONFLICT));

    assertThatThrownBy(() -> client.removeTenant(121L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 409");
  }

  @Test
  void needsFiveActorMode() {
    when(identity.isFiveActorMode()).thenReturn(false);

    assertThatThrownBy(() -> client.findTenantsMarkedForDeletion())
        .isInstanceOf(IllegalStateException.class);
    server.verify();
  }
}
