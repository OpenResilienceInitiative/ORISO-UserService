package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.ApiClient;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaVersionDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * The operator-DPA lookup behind the public onboarding resolve (#569 chain fix round 2). The
 * invitee must see the contract wording before ticking the acceptance box, so the newest published
 * DPA of the platform operator's tenant is served read-only through the anonymous endpoint.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperatorDpaContentClientTest {

  private static final long OPERATOR_TENANT_ID = 1L;
  private static final String DPA_JSON =
      "{\"de\":\"<h2>Auftragsverarbeitung</h2>\",\"en\":\"<h2>Data processing</h2>\"}";

  @Mock private SecurityHeaderSupplier securityHeaderSupplier;
  @Mock private IdentityClient identityClient;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private TenantAdminServiceApiControllerFactory controllerFactory;
  @Mock private TenantControllerApi tenantControllerApi;
  @Mock private ApiClient apiClient;

  @BeforeEach
  void setUp() {
    TechnicalUserConfig technicalUser = new TechnicalUserConfig();
    technicalUser.setUsername("technical");
    technicalUser.setPassword("secret");
    when(identityClientConfig.getTechnicalUser()).thenReturn(technicalUser);
    KeycloakLoginResponseDTO keycloakLogin = new KeycloakLoginResponseDTO();
    keycloakLogin.setAccessToken("token");
    when(identityClient.loginUser(anyString(), anyString())).thenReturn(keycloakLogin);
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(anyString()))
        .thenReturn(new HttpHeaders());
    when(controllerFactory.createControllerApi()).thenReturn(tenantControllerApi);
    when(tenantControllerApi.getApiClient()).thenReturn(apiClient);
  }

  private OperatorDpaContentClient clientFor(long operatorTenantId) {
    return new OperatorDpaContentClient(
        securityHeaderSupplier,
        identityClient,
        identityClientConfig,
        controllerFactory,
        operatorTenantId);
  }

  @Test
  void fetchPublishedDpaContentReturnsTheNewestPublishedVersionOfTheOperatorTenant() {
    when(tenantControllerApi.getDataProcessingAgreementVersions(OPERATOR_TENANT_ID))
        .thenReturn(
            List.of(
                new DpaVersionDTO().activationDate("2026-07-20T10:00").content(DPA_JSON),
                new DpaVersionDTO()
                    .activationDate("2026-01-02T10:00")
                    .content("{\"de\":\"old\"}")));

    assertEquals(DPA_JSON, clientFor(OPERATOR_TENANT_ID).fetchPublishedDpaContent());
  }

  @Test
  void fetchPublishedDpaContentSkipsBlankVersionsAndReturnsTheNewestNonBlankOne() {
    when(tenantControllerApi.getDataProcessingAgreementVersions(OPERATOR_TENANT_ID))
        .thenReturn(
            List.of(
                new DpaVersionDTO().activationDate("2026-07-20T10:00").content("   "),
                new DpaVersionDTO().activationDate("2026-01-02T10:00").content(DPA_JSON)));

    assertEquals(DPA_JSON, clientFor(OPERATOR_TENANT_ID).fetchPublishedDpaContent());
  }

  @Test
  void fetchPublishedDpaReturnsContentAndVersionOfTheNewestPublishedEntry() {
    when(tenantControllerApi.getDataProcessingAgreementVersions(OPERATOR_TENANT_ID))
        .thenReturn(
            List.of(
                new DpaVersionDTO().activationDate("2026-07-20T10:00").content(DPA_JSON),
                new DpaVersionDTO()
                    .activationDate("2026-01-02T10:00")
                    .content("{\"de\":\"old\"}")));

    var dpa = clientFor(OPERATOR_TENANT_ID).fetchPublishedDpa();

    assertEquals(DPA_JSON, dpa.content());
    assertEquals("2026-07-20T10:00", dpa.version());
  }

  /**
   * The recorded signature must name the version whose wording was shown, so content and version
   * always come from the same entry — never the newest date paired with an older text.
   */
  @Test
  void fetchPublishedDpaTakesContentAndVersionFromTheSameEntry() {
    when(tenantControllerApi.getDataProcessingAgreementVersions(OPERATOR_TENANT_ID))
        .thenReturn(
            List.of(
                new DpaVersionDTO().activationDate("2026-07-20T10:00").content("   "),
                new DpaVersionDTO().activationDate("2026-01-02T10:00").content(DPA_JSON)));

    var dpa = clientFor(OPERATOR_TENANT_ID).fetchPublishedDpa();

    assertEquals(DPA_JSON, dpa.content());
    assertEquals("2026-01-02T10:00", dpa.version());
  }

  @Test
  void fetchPublishedDpaReturnsNullVersionWhenUpstreamServesNone() {
    when(tenantControllerApi.getDataProcessingAgreementVersions(OPERATOR_TENANT_ID))
        .thenReturn(List.of(new DpaVersionDTO().content(DPA_JSON)));

    var dpa = clientFor(OPERATOR_TENANT_ID).fetchPublishedDpa();

    assertEquals(DPA_JSON, dpa.content());
    assertNull(dpa.version());
  }

  @Test
  void fetchPublishedDpaReturnsNullWhenNothingIsPublished() {
    when(tenantControllerApi.getDataProcessingAgreementVersions(OPERATOR_TENANT_ID))
        .thenReturn(List.of());

    assertNull(clientFor(OPERATOR_TENANT_ID).fetchPublishedDpa());
  }

  @Test
  void fetchPublishedDpaContentReturnsNullWhenTheOperatorTenantPublishedNothing() {
    when(tenantControllerApi.getDataProcessingAgreementVersions(OPERATOR_TENANT_ID))
        .thenReturn(List.of());

    assertNull(clientFor(OPERATOR_TENANT_ID).fetchPublishedDpaContent());
  }

  @Test
  void fetchPublishedDpaContentReturnsNullAndDoesNotCallUpstreamWhenTheLookupIsDisabled() {
    assertNull(clientFor(0L).fetchPublishedDpaContent());

    verifyNoInteractions(controllerFactory);
    verifyNoInteractions(identityClient);
  }

  @Test
  void fetchPublishedDpaContentReturnsNullWhenUpstreamRejectsTheTechnicalUser() {
    when(tenantControllerApi.getDataProcessingAgreementVersions(OPERATOR_TENANT_ID))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "forbidden", new HttpHeaders(), new byte[0], null));

    assertNull(clientFor(OPERATOR_TENANT_ID).fetchPublishedDpaContent());
  }

  @Test
  void fetchPublishedDpaContentReturnsNullWhenUpstreamIsUnreachable() {
    when(tenantControllerApi.getDataProcessingAgreementVersions(OPERATOR_TENANT_ID))
        .thenThrow(new ResourceAccessException("connection refused"));

    assertNull(clientFor(OPERATOR_TENANT_ID).fetchPublishedDpaContent());
  }

  @Test
  void fetchPublishedDpaContentServesTheCachedTextInsteadOfCallingUpstreamAgain() {
    when(tenantControllerApi.getDataProcessingAgreementVersions(OPERATOR_TENANT_ID))
        .thenReturn(
            List.of(new DpaVersionDTO().activationDate("2026-07-20T10:00").content(DPA_JSON)));
    OperatorDpaContentClient client = clientFor(OPERATOR_TENANT_ID);

    assertEquals(DPA_JSON, client.fetchPublishedDpaContent());
    assertEquals(DPA_JSON, client.fetchPublishedDpaContent());

    verify(tenantControllerApi, times(1)).getDataProcessingAgreementVersions(OPERATOR_TENANT_ID);
  }

  @Test
  void fetchPublishedDpaContentRetriesUpstreamWhileNothingIsPublishedYet() {
    when(tenantControllerApi.getDataProcessingAgreementVersions(OPERATOR_TENANT_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(new DpaVersionDTO().activationDate("2026-07-20T10:00").content(DPA_JSON)));
    OperatorDpaContentClient client = clientFor(OPERATOR_TENANT_ID);

    assertNull(client.fetchPublishedDpaContent());
    assertEquals(DPA_JSON, client.fetchPublishedDpaContent());

    verify(tenantControllerApi, times(2)).getDataProcessingAgreementVersions(OPERATOR_TENANT_ID);
  }

  @Test
  void fetchPublishedDpaContentAuthenticatesAsTheConfiguredTechnicalUser() {
    when(tenantControllerApi.getDataProcessingAgreementVersions(anyLong())).thenReturn(List.of());

    clientFor(OPERATOR_TENANT_ID).fetchPublishedDpaContent();

    verify(identityClient).loginUser("technical", "secret");
    verify(securityHeaderSupplier).getKeycloakAndCsrfHttpHeaders("token");
  }
}
