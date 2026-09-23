package de.caritas.cob.userservice.api.service.email.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * The platform owner's organisation is the "Betreiber" block of Admin → Globale Einstellungen →
 * Dokument-Stammdaten, served unauthenticated by TenantService at {@code /tenant/public/dpia}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformOperatorOrganisationClientTest {

  private static final String TENANT_SERVICE = "http://tenantservice:8080";
  private static final String URL = TENANT_SERVICE + "/tenant/public/dpia";

  @Mock private RestTemplate restTemplate;

  private PlatformOperatorOrganisationClient client() {
    return new PlatformOperatorOrganisationClient(restTemplate, TENANT_SERVICE);
  }

  private void givenOperator(Map<String, Object> operator) {
    when(restTemplate.getForObject(eq(URL), eq(Map.class)))
        .thenReturn(Map.of("operator", operator));
  }

  @Test
  void mapsLegalNameAddressAndBothContactChannels() {
    givenOperator(
        Map.of(
            "legalName", "Betreiber gGmbH",
            "shortName", "Betreiber",
            "address", "Betreiberweg 1, 10115 Berlin",
            "contactEmail", "info@betreiber.example",
            "contactPhone", "030 123456"));

    assertThat(client().fetch())
        .contains(
            new SenderOrganisation(
                "Betreiber gGmbH",
                "Betreiberweg 1, 10115 Berlin",
                "info@betreiber.example · 030 123456"));
  }

  @Test
  void fallsBackToTheShortName_When_noLegalNameIsEntered() {
    givenOperator(Map.of("shortName", "Betreiber", "contactEmail", "info@betreiber.example"));

    assertThat(client().fetch())
        .contains(new SenderOrganisation("Betreiber", null, "info@betreiber.example"));
  }

  @Test
  void isEmpty_When_theOperatorBlockIsBlank() {
    Map<String, Object> blank = new LinkedHashMap<>();
    blank.put("legalName", null);
    blank.put("address", "");
    givenOperator(blank);

    assertThat(client().fetch()).isEmpty();
  }

  @Test
  void isEmpty_When_tenantServiceCannotBeReached() {
    when(restTemplate.getForObject(eq(URL), eq(Map.class)))
        .thenThrow(new ResourceAccessException("down"));

    assertThat(client().fetch()).isEmpty();
  }

  @Test
  void readsOnce_andReusesTheAnswerForAWhile() {
    givenOperator(Map.of("legalName", "Betreiber gGmbH"));
    PlatformOperatorOrganisationClient client = client();

    client.fetch();
    client.fetch();

    verify(restTemplate, times(1)).getForObject(any(String.class), eq(Map.class));
  }
}
