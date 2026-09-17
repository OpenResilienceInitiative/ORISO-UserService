package de.caritas.cob.userservice.api.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyserivce.generated.web.AgencyControllerApi;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyResponseDTO;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

@ExtendWith(MockitoExtension.class)
class EffectiveAgencySettingsLookupTest {

  @InjectMocks private EffectiveAgencySettingsLookup lookup;
  @Mock private AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;
  @Mock private SecurityHeaderSupplier securityHeaderSupplier;
  @Mock private TenantHeaderSupplier tenantHeaderSupplier;
  @Mock private AgencyControllerApi agencyControllerApi;
  @Mock private ApiClient apiClient;

  @BeforeEach
  void setUp() {
    when(agencyServiceApiControllerFactory.createControllerApi()).thenReturn(agencyControllerApi);
    when(agencyControllerApi.getApiClient()).thenReturn(apiClient);
    when(securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders())
        .thenReturn(new HttpHeaders());
  }

  @Test
  void returnsTheEffectiveSettingsServedForTheAgency() {
    var settings =
        new Settings()
            .featureGroupChatV2Enabled(true)
            .featureInternalGroupChatEnabled(true)
            .featureSelfHelpGroupsEnabled(false);
    when(agencyControllerApi.getAgenciesByIds(List.of(42L)))
        .thenReturn(List.of(new AgencyResponseDTO().id(42L).settings(settings)));

    var result = lookup.findEffectiveSettings(42L);

    assertThat(result).isPresent();
    assertThat(result.get().getFeatureGroupChatV2Enabled()).isTrue();
    assertThat(result.get().getFeatureInternalGroupChatEnabled()).isTrue();
    assertThat(result.get().getFeatureSelfHelpGroupsEnabled()).isFalse();
    verify(tenantHeaderSupplier).addTenantHeader(any(HttpHeaders.class));
  }

  @Test
  void returnsEmptyWhenTheAgencyIsUnknown() {
    when(agencyControllerApi.getAgenciesByIds(List.of(42L))).thenReturn(List.of());

    assertThat(lookup.findEffectiveSettings(42L)).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheAgencyServiceAnswersNotFound() {
    when(agencyControllerApi.getAgenciesByIds(List.of(42L)))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

    assertThat(lookup.findEffectiveSettings(42L)).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheAgencyCarriesNoSettings() {
    when(agencyControllerApi.getAgenciesByIds(List.of(42L)))
        .thenReturn(List.of(new AgencyResponseDTO().id(42L)));

    assertThat(lookup.findEffectiveSettings(42L)).isEmpty();
  }

  @Test
  void propagatesTransportFailuresSoCallersCanFallBack() {
    when(agencyControllerApi.getAgenciesByIds(List.of(42L)))
        .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

    assertThatThrownBy(() -> lookup.findEffectiveSettings(42L))
        .isInstanceOf(HttpServerErrorException.class);
  }
}
