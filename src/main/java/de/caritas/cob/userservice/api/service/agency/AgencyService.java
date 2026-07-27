package de.caritas.cob.userservice.api.service.agency;

import static java.util.Collections.emptyList;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.agencyserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyserivce.generated.web.AgencyControllerApi;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.service.cache.SharedReadCache;
import de.caritas.cob.userservice.api.service.cache.SharedReadCache.CacheName;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** Service class to communicate with the AgencyService. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgencyService {

  private static final TypeReference<List<AgencyDTO>> AGENCY_LIST_TYPE = new TypeReference<>() {};

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull TenantHeaderSupplier tenantHeaderSupplier;
  private final @NonNull AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;
  private final @NonNull SharedReadCache sharedReadCache;

  /**
   * Returns the {@link AgencyDTO} for the provided agencyId. Agency will be cached for further
   * requests.
   *
   * @param agencyId {@link AgencyDTO#getId()}
   * @return AgencyDTO {@link AgencyDTO}
   */
  public AgencyDTO getAgency(Long agencyId) {
    return sharedReadCache.getOrLoad(
        CacheName.AGENCY,
        tenantKey("id:" + agencyId),
        AgencyDTO.class,
        () ->
            getAgenciesFromAgencyService(Collections.singletonList(agencyId)).stream()
                .findFirst()
                .orElse(null));
  }

  /**
   * Returns the {@link AgencyDTO} for the provided agencyId. Agency won't be cached for further
   * requests.
   *
   * @param agencyId {@link AgencyDTO#getId()}
   * @return AgencyDTO {@link AgencyDTO}
   */
  public AgencyDTO getAgencyWithoutCaching(Long agencyId) {
    return getAgenciesFromAgencyService(Collections.singletonList(agencyId)).stream()
        .findFirst()
        .orElse(null);
  }

  /**
   * Returns List of {@link AgencyDTO} for provided agencyIds. Agencies will be cached for further
   * requests.
   *
   * @param agencyIds List of {@link AgencyDTO#getId()}
   * @return List<AgencyDTO> List of {@link AgencyDTO}
   */
  public List<AgencyDTO> getAgencies(List<Long> agencyIds) {
    if (!isNotEmpty(agencyIds)) {
      return emptyList();
    }
    var agencies =
        sharedReadCache.getOrLoadTyped(
            CacheName.AGENCY,
            tenantKey("ids:" + agencyIds),
            AGENCY_LIST_TYPE,
            () -> getAgenciesFromAgencyService(agencyIds));
    cacheAgenciesById(agencies);
    return agencies;
  }

  private void cacheAgenciesById(List<AgencyDTO> agencies) {
    if (agencies == null) {
      return;
    }
    agencies.stream()
        .filter(Objects::nonNull)
        .filter(agency -> agency.getId() != null)
        .forEach(
            agency ->
                sharedReadCache.put(CacheName.AGENCY, tenantKey("id:" + agency.getId()), agency));
  }

  public List<AgencyDTO> getAgenciesNotCached(List<Long> agencyIds) {
    return getAgenciesFromAgencyService(agencyIds);
  }

  /**
   * Returns List of {@link AgencyDTO} for provided agencyIds.
   *
   * @param agencyIds List of {@link AgencyDTO#getId()}
   * @return List<AgencyDTO> List of {@link AgencyDTO}
   */
  private List<AgencyDTO> getAgenciesFromAgencyService(List<Long> agencyIds) {
    if (isNotEmpty(agencyIds)) {
      try {
        AgencyControllerApi agencyControllerApi = this.getAgencyControllerApi();
        addDefaultHeaders(agencyControllerApi.getApiClient());
        return agencyControllerApi.getAgenciesByIds(agencyIds).stream()
            .map(this::fromOriginalAgency)
            .collect(Collectors.toList());
      } catch (HttpClientErrorException httpClientErrorException) {
        if (HttpStatus.NOT_FOUND.equals(httpClientErrorException.getStatusCode())) {
          log.warn(
              "AgencyService returned 404 for agency ids {}. Treating as missing agencies for local hybrid registration.",
              agencyIds);
          return emptyList();
        }
        throw httpClientErrorException;
      }
    }
    return emptyList();
  }

  private AgencyControllerApi getAgencyControllerApi() {
    return agencyServiceApiControllerFactory.createControllerApi();
  }

  /**
   * Returns a list of {@link AgencyDTO} for the provided consulting type.
   *
   * @param consultingTypeId
   * @return List of {@link AgencyDTO}
   */
  public List<AgencyDTO> getAgenciesByConsultingType(int consultingTypeId) {
    var agencyControllerApi = getAgencyControllerApi();
    addDefaultHeaders(agencyControllerApi.getApiClient());
    return agencyControllerApi.getAgenciesByConsultingType(consultingTypeId).stream()
        .map(this::fromOriginalAgency)
        .collect(Collectors.toList());
  }

  private void addDefaultHeaders(ApiClient apiClient) {
    // Registration/public flows must stay unauthenticated. Only forward Authorization when a real
    // user token already exists in request context.
    var headers = this.securityHeaderSupplier.getOptionalKeycloakAndCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }

  private AgencyDTO fromOriginalAgency(AgencyResponseDTO agencyResponseDTO) {
    var objectMapper = new ObjectMapper();
    try {
      return objectMapper.readValue(
          objectMapper.writeValueAsString(agencyResponseDTO), AgencyDTO.class);
    } catch (JsonProcessingException e) {
      throw new InternalServerErrorException(
          "Model definition of agency in userservice does not "
              + "match the definition of agencyservice");
    }
  }

  /**
   * Returns the {@link AgencyDTO} for the provided agencyId. Agency won't be cached for further
   * requests.
   *
   * @param agencyIds the List of agency ids
   */
  public List<AgencyDTO> getAgenciesWithoutCaching(List<Long> agencyIds) {
    return getAgenciesFromAgencyService(agencyIds);
  }

  private String tenantKey(String key) {
    return "tenant:" + String.valueOf(TenantContext.getCurrentTenant()) + ":" + key;
  }
}
