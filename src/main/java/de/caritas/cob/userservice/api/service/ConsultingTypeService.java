package de.caritas.cob.userservice.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import de.caritas.cob.userservice.api.config.apiclient.ConsultingTypeServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.cache.SharedReadCache;
import de.caritas.cob.userservice.api.service.cache.SharedReadCache.CacheName;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.consultingtypeservice.generated.ApiClient;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.BasicConsultingTypeResponseDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/** Service class to communicate with the ConsultingTypeService. */
@Component
@RequiredArgsConstructor
public class ConsultingTypeService {

  private static final TypeReference<List<Integer>> CONSULTING_TYPE_ID_LIST_TYPE =
      new TypeReference<>() {};

  private final @NonNull ConsultingTypeServiceApiControllerFactory
      consultingTypeServiceApiControllerFactory;
  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull TenantHeaderSupplier tenantHeaderSupplier;
  private final @NonNull SharedReadCache sharedReadCache;

  /**
   * Returns the {@link ExtendedConsultingTypeResponseDTO} for the provided consulting type ID. the
   * ExtendedConsultingTypeResponseDTO will be cached for further requests.
   *
   * @param consultingTypeId the consulting type ID for the extended consulting type response DTO
   * @return ExtendedConsultingTypeResponseDTO {@link ExtendedConsultingTypeResponseDTO}
   */
  public ExtendedConsultingTypeResponseDTO getExtendedConsultingTypeResponseDTO(
      int consultingTypeId) throws RestClientException {
    return sharedReadCache.getOrLoad(
        CacheName.CONSULTING_TYPE,
        tenantKey("id:" + consultingTypeId),
        ExtendedConsultingTypeResponseDTO.class,
        () -> loadExtendedConsultingType(consultingTypeId));
  }

  private ExtendedConsultingTypeResponseDTO loadExtendedConsultingType(int consultingTypeId) {
    var consultingTypeControllerApi =
        consultingTypeServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(consultingTypeControllerApi.getApiClient());
    return consultingTypeControllerApi.getExtendedConsultingTypeById(consultingTypeId);
  }

  /**
   * Returns all existing consulting type ids. the id´s will be cached for further requests.
   *
   * @return list with consulting type ids
   */
  public List<Integer> getAllConsultingTypeIds(Long tenantId) {
    Long effectiveTenant = tenantId != null ? tenantId : TenantContext.getCurrentTenant();
    return sharedReadCache.getOrLoadTyped(
        CacheName.CONSULTING_TYPE,
        "tenant:" + String.valueOf(effectiveTenant) + ":all-ids",
        CONSULTING_TYPE_ID_LIST_TYPE,
        this::loadAllConsultingTypeIds);
  }

  private List<Integer> loadAllConsultingTypeIds() {
    var consultingTypeControllerApi =
        consultingTypeServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(consultingTypeControllerApi.getApiClient());
    return consultingTypeControllerApi.getBasicConsultingTypeList().stream()
        .map(BasicConsultingTypeResponseDTO::getId)
        .collect(Collectors.toList());
  }

  private void addDefaultHeaders(ApiClient apiClient) {
    var headers = this.securityHeaderSupplier.getCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }

  private String tenantKey(String key) {
    return "tenant:" + String.valueOf(TenantContext.getCurrentTenant()) + ":" + key;
  }
}
