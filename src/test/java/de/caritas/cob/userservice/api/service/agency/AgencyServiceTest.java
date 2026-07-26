package de.caritas.cob.userservice.api.service.agency;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.AGENCY_DTO_SUCHT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.AGENCY_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.AGENCY_ID_LIST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import de.caritas.cob.userservice.agencyserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyserivce.generated.web.AgencyControllerApi;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.service.httpheader.HttpHeadersResolver;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgencyServiceTest {

  @InjectMocks AgencyService agencyService;

  @Mock AgencyControllerApi agencyControllerApi;

  @Mock AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;

  @Mock TenantHeaderSupplier tenantHeaderSupplier;

  @Mock SecurityHeaderSupplier securityHeaderSupplier;

  @Mock ApiClient apiClient;

  @BeforeEach
  void setUp() {
    lenient()
        .when(agencyServiceApiControllerFactory.createControllerApi())
        .thenReturn(agencyControllerApi);
    lenient().when(agencyControllerApi.getApiClient()).thenReturn(apiClient);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @ParameterizedTest
  @NullAndEmptySource
  void getAgenciesFromAgencyService_Should_returnEmptyList_When_nullPassed(List<Long> emptyIds) {
    List<AgencyDTO> result = this.agencyService.getAgencies(emptyIds);
    assertThat(result).isEmpty();
  }

  @Test
  void getAgencyWithoutCaching_Should_returnNull_When_agencyDoesNotExist() {
    when(securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders())
        .thenReturn(new HttpHeaders());
    when(this.agencyControllerApi.getApiClient()).thenReturn(apiClient);
    when(agencyServiceApiControllerFactory.createControllerApi()).thenReturn(agencyControllerApi);
    when(this.agencyControllerApi.getAgenciesByIds(Lists.newArrayList(1L)))
        .thenReturn(Lists.newArrayList());

    assertThat(this.agencyService.getAgencyWithoutCaching(1L)).isNull();
  }

  @Test
  void getAgencyWithoutCaching_Should_returnNull_When_agencyServiceReturns404() {
    when(securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders())
        .thenReturn(new HttpHeaders());
    when(this.agencyControllerApi.getApiClient()).thenReturn(apiClient);
    when(agencyServiceApiControllerFactory.createControllerApi()).thenReturn(agencyControllerApi);
    when(this.agencyControllerApi.getAgenciesByIds(Lists.newArrayList(1L)))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

    assertThat(this.agencyService.getAgencyWithoutCaching(1L)).isNull();
  }

  @Test
  void getAgenciesFromAgencyService_Should_passTenantId() {
    TenantContext.setCurrentTenant(1L);
    TenantHeaderSupplier tenantHeaderSupplier = new TenantHeaderSupplier(new HttpHeadersResolver());
    ReflectionTestUtils.setField(tenantHeaderSupplier, "multitenancy", true);
    ReflectionTestUtils.setField(agencyService, "tenantHeaderSupplier", tenantHeaderSupplier);
    HttpHeaders headers = new HttpHeaders();
    when(securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders()).thenReturn(headers);
    when(this.agencyControllerApi.getAgenciesByIds(Lists.newArrayList(1L)))
        .thenReturn(List.of(new AgencyResponseDTO()));

    this.agencyService.getAgency(1L);

    assertThat(headers.get("tenantId").get(0)).isEqualTo("1");
  }

  @Test
  void getAgency_Should_returnMappedAgency_When_agencyFound() {
    stubAgencyLookup(List.of(toAgencyResponseDTO(AGENCY_DTO_SUCHT)));

    AgencyDTO result = agencyService.getAgency(AGENCY_ID);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(AGENCY_ID);
    assertThat(result.getName()).isEqualTo(AGENCY_DTO_SUCHT.getName());
  }

  @Test
  void getAgencyWithoutCaching_Should_returnAgency() {
    stubAgencyLookup(List.of(new AgencyResponseDTO()));

    AgencyDTO result = agencyService.getAgencyWithoutCaching(1L);

    assertThat(result).isNotNull();
  }

  @Test
  void getAgencyWithoutCaching_Should_returnNull_When_agencyNotFound() {
    stubAgencyLookup(List.of());

    AgencyDTO result = agencyService.getAgencyWithoutCaching(1L);

    assertThat(result).isNull();
  }

  @Test
  void getAgency_Should_returnNull_When_agencyNotFound() {
    stubAgencyLookup(List.of());

    AgencyDTO result = agencyService.getAgency(1L);

    assertThat(result).isNull();
  }

  @Test
  void getAgenciesNotCached_Should_returnEmptyList_When_emptyIdsProvided() {
    List<AgencyDTO> result = agencyService.getAgenciesNotCached(List.of());
    assertThat(result).isEmpty();
  }

  @Test
  void getAgenciesNotCached_Should_returnAgencies_When_idsProvided() {
    stubAgencyLookup(List.of(new AgencyResponseDTO()));

    List<AgencyDTO> result = agencyService.getAgenciesNotCached(Lists.newArrayList(1L));

    assertThat(result).hasSize(1);
  }

  @Test
  void getAgencies_Should_returnMappedAgencies_When_idsProvided() {
    stubAgencyLookup(List.of(toAgencyResponseDTO(AGENCY_DTO_SUCHT)));

    List<AgencyDTO> result = agencyService.getAgencies(AGENCY_ID_LIST);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getId()).isEqualTo(AGENCY_ID);
  }

  @Test
  void getAgenciesWithoutCaching_Should_returnEmptyList_When_emptyIdsProvided() {
    List<AgencyDTO> result = agencyService.getAgenciesWithoutCaching(List.of());
    assertThat(result).isEmpty();
  }

  @Test
  void getAgenciesWithoutCaching_Should_returnMappedAgencies_When_idsProvided() {
    stubAgencyLookup(List.of(toAgencyResponseDTO(AGENCY_DTO_SUCHT)));

    List<AgencyDTO> result = agencyService.getAgenciesWithoutCaching(AGENCY_ID_LIST);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getConsultingType()).isEqualTo(AGENCY_DTO_SUCHT.getConsultingType());
  }

  @Test
  void getAgenciesByConsultingType_Should_returnMappedAgencies() {
    HttpHeaders headers = new HttpHeaders();
    when(securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders()).thenReturn(headers);
    when(agencyControllerApi.getAgenciesByConsultingType(1))
        .thenReturn(List.of(new AgencyResponseDTO()));

    List<AgencyDTO> result = agencyService.getAgenciesByConsultingType(1);

    assertThat(result).hasSize(1);
    verify(tenantHeaderSupplier).addTenantHeader(headers);
  }

  @Test
  void getAgenciesByConsultingType_Should_addAuthorizationHeader_When_tokenPresent() {
    HttpHeaders headers = new HttpHeaders();
    headers.add("Authorization", "Bearer token");
    when(securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders()).thenReturn(headers);
    when(agencyControllerApi.getAgenciesByConsultingType(1))
        .thenReturn(List.of(new AgencyResponseDTO()));

    agencyService.getAgenciesByConsultingType(1);

    verify(apiClient).addDefaultHeader(eq("Authorization"), eq("Bearer token"));
    verify(tenantHeaderSupplier).addTenantHeader(headers);
  }

  @Test
  void getAgenciesByConsultingType_Should_throwInternalServerErrorException_When_mappingFails() {
    HttpHeaders headers = new HttpHeaders();
    when(securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders()).thenReturn(headers);
    when(agencyControllerApi.getAgenciesByConsultingType(1))
        .thenReturn(List.of(new AgencyResponseDTO()));

    try (MockedConstruction<ObjectMapper> ignored =
        mockConstruction(
            ObjectMapper.class,
            (mock, context) ->
                when(mock.writeValueAsString(any()))
                    .thenThrow(new JsonProcessingException("mapping failed") {}))) {
      assertThatThrownBy(() -> agencyService.getAgenciesByConsultingType(1))
          .isInstanceOf(InternalServerErrorException.class)
          .hasMessageContaining("does not match");
    }
  }

  private void stubAgencyLookup(List<AgencyResponseDTO> agencyResponseDTOS) {
    HttpHeaders headers = new HttpHeaders();
    when(securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders()).thenReturn(headers);
    when(agencyControllerApi.getAgenciesByIds(any())).thenReturn(agencyResponseDTOS);
  }

  @SneakyThrows
  private AgencyResponseDTO toAgencyResponseDTO(AgencyDTO agencyDTO) {
    ObjectMapper objectMapper = new ObjectMapper();
    return objectMapper.readValue(
        objectMapper.writeValueAsString(agencyDTO), AgencyResponseDTO.class);
  }
}
