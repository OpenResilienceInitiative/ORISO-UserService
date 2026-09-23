package de.caritas.cob.userservice.api.service.email.sender;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Optional;

/** A real {@link SenderOrganisationResolver} over canned Admin data instead of TenantService. */
public final class SenderOrganisationFixture {

  /** The platform owner as a test enters it — deliberately not a "Musterstraße" sample. */
  public static final SenderOrganisation PLATFORM_OWNER =
      new SenderOrganisation("ORISO", "Betreiberweg 1, 10115 Berlin", "info@betreiber.example");

  private SenderOrganisationFixture() {}

  public static SenderOrganisationResolver platformOwner() {
    return resolving(PLATFORM_OWNER, Map.of());
  }

  public static SenderOrganisationResolver nobody() {
    return resolving(null, Map.of());
  }

  /**
   * The real Träger mapping over a canned TenantService answer: {@code GET /tenant/{id}} returns
   * {@code tenant} for its id, as the technical user would read it.
   */
  public static SenderOrganisationResolver resolvingTraegerFromTenantService(
      SenderOrganisation platform,
      de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO tenant) {
    PlatformOperatorOrganisationClient platformClient =
        mock(PlatformOperatorOrganisationClient.class);
    lenient().when(platformClient.fetch()).thenReturn(Optional.ofNullable(platform));

    var controllerFactory =
        mock(
            de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory
                .class);
    var tenantApi =
        mock(de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi.class);
    lenient().when(controllerFactory.createControllerApi()).thenReturn(tenantApi);
    lenient()
        .when(tenantApi.getApiClient())
        .thenReturn(mock(de.caritas.cob.userservice.tenantadminservice.generated.ApiClient.class));
    lenient().when(tenantApi.getTenantById(tenant.getId())).thenReturn(tenant);

    var technicalUser = new de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig();
    technicalUser.setUsername("technical");
    technicalUser.setPassword("secret");
    var identityClientConfig =
        mock(de.caritas.cob.userservice.api.port.out.IdentityClientConfig.class);
    lenient().when(identityClientConfig.getTechnicalUser()).thenReturn(technicalUser);
    var identityAuthentication =
        mock(de.caritas.cob.userservice.api.port.out.IdentityAuthentication.class);
    lenient()
        .when(identityAuthentication.login(any(), any()))
        .thenReturn(new de.caritas.cob.userservice.api.port.out.IdentityLogin("token", 0, 0, null));
    var securityHeaderSupplier =
        mock(de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier.class);
    lenient()
        .when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(any()))
        .thenReturn(new org.springframework.http.HttpHeaders());

    return new SenderOrganisationResolver(
        platformClient,
        new TraegerOrganisationClient(
            securityHeaderSupplier,
            identityAuthentication,
            identityClientConfig,
            controllerFactory));
  }

  /**
   * @param platform the platform owner's data, or {@code null} for "nothing entered"
   * @param traeger each Träger's own data by tenant id; absent ids have none
   */
  public static SenderOrganisationResolver resolving(
      SenderOrganisation platform, Map<Long, SenderOrganisation> traeger) {
    PlatformOperatorOrganisationClient platformClient =
        mock(PlatformOperatorOrganisationClient.class);
    lenient().when(platformClient.fetch()).thenReturn(Optional.ofNullable(platform));
    TraegerOrganisationClient traegerClient = mock(TraegerOrganisationClient.class);
    lenient()
        .when(traegerClient.fetch(any()))
        .thenAnswer(
            invocation -> Optional.ofNullable(traeger.get(invocation.<Long>getArgument(0))));
    return new SenderOrganisationResolver(platformClient, traegerClient);
  }
}
