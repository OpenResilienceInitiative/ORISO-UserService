package de.caritas.cob.userservice.api.service.email.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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

  @Mock
  private de.caritas.cob.userservice.api.service.notification.TenantSystemEmailClient contextClient;

  private TraegerOrganisationClient client;

  @BeforeEach
  void setUp() {
    client = new TraegerOrganisationClient(contextClient);
  }

  // --- Träger legal name and contact (Frank, 2026-09-23) ---

  @Test
  void namesTheTraegerByItsFullLegalName_When_itEnteredOne() {
    when(contextClient.readTenant(TRAEGER_ID))
        .thenReturn(
            context(
                new TenantDTO()
                    .id(TRAEGER_ID)
                    .name("Caritas Nord")
                    .legalName("Caritasverband für die Erzdiözese Nord e.V.")));

    assertThat(client.fetch(TRAEGER_ID))
        .contains(
            new SenderOrganisation("Caritasverband für die Erzdiözese Nord e.V.", null, null));
  }

  @Test
  void fallsBackToTheDisplayName_When_theLegalNameIsBlank() {
    when(contextClient.readTenant(TRAEGER_ID))
        .thenReturn(context(new TenantDTO().id(TRAEGER_ID).name("Caritas Nord").legalName("  ")));

    assertThat(client.fetch(TRAEGER_ID))
        .contains(new SenderOrganisation("Caritas Nord", null, null));
  }

  @Test
  void buildsTheContactLineFromTheTraegersEmailAndPhone_likeThePlatformOwners() {
    when(contextClient.readTenant(TRAEGER_ID))
        .thenReturn(
            context(
                new TenantDTO()
                    .id(TRAEGER_ID)
                    .name("Caritas Nord")
                    .contactEmail("beratung@caritas-nord.example")
                    .contactPhone("+49 431 123-0")));

    assertThat(client.fetch(TRAEGER_ID))
        .contains(
            new SenderOrganisation(
                "Caritas Nord", null, "beratung@caritas-nord.example · +49 431 123-0"));
  }

  @Test
  void buildsTheContactLineFromWhatIsThere_When_onlyThePhoneWasEntered() {
    when(contextClient.readTenant(TRAEGER_ID))
        .thenReturn(
            context(
                new TenantDTO().id(TRAEGER_ID).name("Caritas Nord").contactPhone("+49 431 123-0")));

    assertThat(client.fetch(TRAEGER_ID))
        .contains(new SenderOrganisation("Caritas Nord", null, "+49 431 123-0"));
  }

  @Test
  void isEmpty_When_theTenantIsOnlyReserved() {
    when(contextClient.readTenant(anyLong()))
        .thenThrow(
            HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

    assertThat(client.fetch(TRAEGER_ID)).isEmpty();
  }

  @Test
  void isEmpty_When_theTechnicalUserMayNotReadTenants() {
    when(contextClient.readTenant(anyLong()))
        .thenThrow(
            HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

    assertThat(client.fetch(TRAEGER_ID)).isEmpty();
  }

  @Test
  void neverLogsIn_forThePlatformTenantOrNoTenant() {
    assertThat(client.fetch(null)).isEmpty();
    assertThat(client.fetch(0L)).isEmpty();
    verifyNoInteractions(contextClient);
  }

  private static java.util.Map<String, Object> context(TenantDTO tenant) {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .convertValue(tenant, java.util.Map.class);
  }
}
