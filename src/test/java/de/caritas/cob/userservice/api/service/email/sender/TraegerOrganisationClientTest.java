package de.caritas.cob.userservice.api.service.email.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.ApiClient;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
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

/**
 * A Träger's own organisation data: its legal name (else its display name), the address it entered
 * at onboarding (or a platform admin entered under Träger → Allgemein) and its contact e-mail and
 * phone. The address is not in the public tenant view, so the read goes through the admin view as
 * the technical user.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TraegerOrganisationClientTest {

  private static final long TRAEGER_ID = 84L;

  @Mock private SecurityHeaderSupplier securityHeaderSupplier;
  @Mock private IdentityAuthentication identityAuthentication;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private TenantAdminServiceApiControllerFactory controllerFactory;
  @Mock private TenantControllerApi tenantControllerApi;
  @Mock private ApiClient apiClient;

  private TraegerOrganisationClient client;

  @BeforeEach
  void setUp() {
    TechnicalUserConfig technicalUser = new TechnicalUserConfig();
    technicalUser.setUsername("technical");
    technicalUser.setPassword("secret");
    when(identityClientConfig.getTechnicalUser()).thenReturn(technicalUser);
    when(identityAuthentication.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("token", 0, 0, null));
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(anyString()))
        .thenReturn(new HttpHeaders());
    when(controllerFactory.createControllerApi()).thenReturn(tenantControllerApi);
    when(tenantControllerApi.getApiClient()).thenReturn(apiClient);
    client =
        new TraegerOrganisationClient(
            securityHeaderSupplier,
            identityAuthentication,
            identityClientConfig,
            controllerFactory);
  }

  @Test
  void mapsNameAndAddress_andHasNoContactLine_When_theTraegerEnteredNoContact() {
    when(tenantControllerApi.getTenantById(TRAEGER_ID))
        .thenReturn(
            new TenantDTO().id(TRAEGER_ID).name("Träger Nord e.V.").address("Nordstraße 5, Kiel"));

    assertThat(client.fetch(TRAEGER_ID))
        .contains(new SenderOrganisation("Träger Nord e.V.", "Nordstraße 5, Kiel", null));
  }

  // --- Träger legal name and contact (Frank, 2026-09-23) ---

  @Test
  void namesTheTraegerByItsFullLegalName_When_itEnteredOne() {
    when(tenantControllerApi.getTenantById(TRAEGER_ID))
        .thenReturn(
            new TenantDTO()
                .id(TRAEGER_ID)
                .name("Caritas Nord")
                .legalName("Caritasverband für die Erzdiözese Nord e.V."));

    assertThat(client.fetch(TRAEGER_ID))
        .contains(
            new SenderOrganisation("Caritasverband für die Erzdiözese Nord e.V.", null, null));
  }

  @Test
  void fallsBackToTheDisplayName_When_theLegalNameIsBlank() {
    when(tenantControllerApi.getTenantById(TRAEGER_ID))
        .thenReturn(new TenantDTO().id(TRAEGER_ID).name("Caritas Nord").legalName("  "));

    assertThat(client.fetch(TRAEGER_ID))
        .contains(new SenderOrganisation("Caritas Nord", null, null));
  }

  @Test
  void buildsTheContactLineFromTheTraegersEmailAndPhone_likeThePlatformOwners() {
    when(tenantControllerApi.getTenantById(TRAEGER_ID))
        .thenReturn(
            new TenantDTO()
                .id(TRAEGER_ID)
                .name("Caritas Nord")
                .contactEmail("beratung@caritas-nord.example")
                .contactPhone("+49 431 123-0"));

    assertThat(client.fetch(TRAEGER_ID))
        .contains(
            new SenderOrganisation(
                "Caritas Nord", null, "beratung@caritas-nord.example · +49 431 123-0"));
  }

  @Test
  void buildsTheContactLineFromWhatIsThere_When_onlyThePhoneWasEntered() {
    when(tenantControllerApi.getTenantById(TRAEGER_ID))
        .thenReturn(
            new TenantDTO().id(TRAEGER_ID).name("Caritas Nord").contactPhone("+49 431 123-0"));

    assertThat(client.fetch(TRAEGER_ID))
        .contains(new SenderOrganisation("Caritas Nord", null, "+49 431 123-0"));
  }

  @Test
  void isEmpty_When_theTenantIsOnlyReserved() {
    when(tenantControllerApi.getTenantById(anyLong()))
        .thenThrow(
            HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

    assertThat(client.fetch(TRAEGER_ID)).isEmpty();
  }

  @Test
  void isEmpty_When_theTechnicalUserMayNotReadTenants() {
    when(tenantControllerApi.getTenantById(anyLong()))
        .thenThrow(
            HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

    assertThat(client.fetch(TRAEGER_ID)).isEmpty();
  }

  @Test
  void neverLogsIn_forThePlatformTenantOrNoTenant() {
    assertThat(client.fetch(null)).isEmpty();
    assertThat(client.fetch(0L)).isEmpty();
    verifyNoInteractions(identityAuthentication);
  }
}
