package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTenantDTO;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * ORISO-Helm#367: the service identity may only create a tenant by consuming a reservation the
 * invite already holds. The wire must therefore always carry the reserved ID together with its
 * reservation token, and without that pair the client must not call TenantService at all.
 */
class TenantCreationClientTest {

  private final RestTemplate rest = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.createServer(rest);
  private final IdentityAuthentication identity = mock(IdentityAuthentication.class);
  private final IdentityClientConfig config = mock(IdentityClientConfig.class);
  private final SecurityHeaderSupplier headers =
      new SecurityHeaderSupplier(mock(AuthenticatedUser.class));
  private final TenantAdminServiceApiControllerFactory factory =
      new TenantAdminServiceApiControllerFactory();
  private final TenantCreationClient client =
      new TenantCreationClient(headers, identity, config, factory);

  @BeforeEach
  void setUp() {
    var technical = new TechnicalUserConfig();
    technical.setUsername("synthetic-service");
    technical.setPassword("synthetic-password");
    when(config.getTechnicalUser()).thenReturn(technical);
    when(identity.login("synthetic-service", "synthetic-password"))
        .thenReturn(new IdentityLogin("synthetic-token", 60, 120, "synthetic-refresh"));
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "X-CSRF-TOKEN");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "CSRF-TOKEN");
    ReflectionTestUtils.setField(factory, "tenantServiceApiUrl", "https://tenant.example.org");
    ReflectionTestUtils.setField(factory, "restTemplate", rest);
  }

  @Test
  void createTenantSendsTheReservedIdAndItsReservationTokenOnTheWire() {
    var sentBody = new AtomicReference<String>();
    server
        .expect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer synthetic-token"))
        .andExpect(request -> sentBody.set(((MockClientHttpRequest) request).getBodyAsString()))
        .andRespond(withSuccess("{\"id\":88,\"name\":\"Org\"}", MediaType.APPLICATION_JSON));

    var created =
        client.createTenant(
            new MultilingualTenantDTO()
                .id(88L)
                .name("Org")
                .tenantIdReservationToken("synthetic-reservation"));

    assertThat(created.getId()).isEqualTo(88L);
    server.verify();
    var body = JsonMapper.builder().build().readTree(sentBody.get());
    assertThat(body.path("id").asLong()).isEqualTo(88L);
    assertThat(body.path("tenantIdReservationToken").asText()).isEqualTo("synthetic-reservation");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void createTenantWithoutAReservationTokenNeverReachesTenantService(String token) {
    assertThatThrownBy(
            () ->
                client.createTenant(
                    new MultilingualTenantDTO()
                        .id(88L)
                        .name("Org")
                        .tenantIdReservationToken(token)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reservation");

    verifyNoInteractions(identity);
    server.verify();
  }

  @Test
  void createTenantWithoutTheReservedIdNeverReachesTenantService() {
    assertThatThrownBy(
            () ->
                client.createTenant(
                    new MultilingualTenantDTO()
                        .name("Org")
                        .tenantIdReservationToken("synthetic-reservation")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reservation");

    verifyNoInteractions(identity);
    server.verify();
  }
}
