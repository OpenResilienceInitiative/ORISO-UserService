package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.*;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.httpheader.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Actual generated owner clients/headers; the server and bearer issuer are synthetic. */
class OrdinaryCreationTenantResolverTest {
  private static final String TOKEN = "synthetic-runtime-bearer";
  private final RestTemplate http = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
  private final IdentityClientConfig identities = mock(IdentityClientConfig.class);
  private final AuthenticatedUser human = mock(AuthenticatedUser.class);

  private OrdinaryCreationTenantResolver resolver() {
    var tenantFactory = new TenantServiceApiControllerFactory();
    ReflectionTestUtils.setField(tenantFactory, "restTemplate", http);
    ReflectionTestUtils.setField(tenantFactory, "tenantServiceApiUrl", "https://tenant.example");
    var agencyFactory = new AgencyServiceApiControllerFactory();
    ReflectionTestUtils.setField(agencyFactory, "restTemplate", http);
    ReflectionTestUtils.setField(agencyFactory, "agencyServiceApiUrl", "https://agency.example");
    var headers = new SecurityHeaderSupplier(human);
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "X-CSRF-TOKEN");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "CSRF-TOKEN");
    var agencies =
        new AgencyService(
            headers,
            mock(TenantHeaderSupplier.class),
            agencyFactory,
            new ConcurrentMapCacheManager());
    return new OrdinaryCreationTenantResolver(
        new TenantService(tenantFactory, new ConcurrentMapCacheManager()), agencies, identities);
  }

  private void boundedRuntime() {
    when(identities.isFiveActorMode()).thenReturn(true);
    when(identities.taskAccessToken(TaskPurpose.RUNTIME_POLICY)).thenReturn(TOKEN);
  }

  @Test
  void configuredSingleOwnerReadSendsOnlyTheExplicitRuntimeBearer() {
    boundedRuntime();
    server
        .expect(requestTo("https://tenant.example/tenant/public/single"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
        .andRespond(withSuccess("{\"id\":79}", MediaType.APPLICATION_JSON));
    assertEquals(79L, resolver().resolve(null, null));
    server.verify();
    verify(identities).taskAccessToken(TaskPurpose.RUNTIME_POLICY);
    verify(identities, never()).getTechnicalUser();
    verifyNoInteractions(human);
  }

  @Test
  void currentAgencyOwnerUsesRuntimeAndPreservesTheExactRequestedAgencySet() {
    boundedRuntime();
    server
        .expect(requestTo("https://agency.example/agencies/275"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
        .andRespond(withSuccess("[{\"id\":275,\"tenantId\":79}]", MediaType.APPLICATION_JSON));
    assertEquals(79L, resolver().resolve(null, List.of(275L)));
    server.verify();
    verifyNoInteractions(human);
  }

  @Test
  void foreignRequestedTenantCannotChooseAnotherOwner() {
    boundedRuntime();
    server
        .expect(requestTo("https://agency.example/agencies/275"))
        .andRespond(withSuccess("[{\"id\":275,\"tenantId\":79}]", MediaType.APPLICATION_JSON));
    assertThrows(AccessDeniedException.class, () -> resolver().resolve(80L, List.of(275L)));
    server.verify();
  }

  @Test
  void agenciesOfDifferentOwnersCannotManufactureASingleTenant() {
    boundedRuntime();
    server
        .expect(request -> assertTrue(request.getURI().getPath().startsWith("/agencies/")))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
        .andRespond(
            withSuccess(
                "[{\"id\":275,\"tenantId\":79},{\"id\":276,\"tenantId\":80}]",
                MediaType.APPLICATION_JSON));
    assertThrows(AccessDeniedException.class, () -> resolver().resolve(null, List.of(275L, 276L)));
    server.verify();
  }

  @Test
  void anotherAgencyProjectionCannotReplaceTheRequestedAgency() {
    boundedRuntime();
    server
        .expect(requestTo("https://agency.example/agencies/275"))
        .andRespond(withSuccess("[{\"id\":276,\"tenantId\":79}]", MediaType.APPLICATION_JSON));
    assertThrows(ResponseStatusException.class, () -> resolver().resolve(null, List.of(275L)));
    server.verify();
  }

  @Test
  void missingOrTechnicalSingleOwnerFailsClosedWithoutInventingTenantZero() {
    boundedRuntime();
    server
        .expect(requestTo("https://tenant.example/tenant/public/single"))
        .andRespond(withSuccess("{\"id\":0}", MediaType.APPLICATION_JSON));
    assertThrows(ResponseStatusException.class, () -> resolver().resolve(null, List.of()));
    server.verify();
  }

  @Test
  void groupedCreationDoesNotFallBackToLegacyAuthentication() {
    assertThrows(ResponseStatusException.class, () -> resolver().resolve(null, List.of()));
    verify(identities, never()).getTechnicalUser();
    verify(identities, never()).taskAccessToken(any());
    server.verify();
  }
}
