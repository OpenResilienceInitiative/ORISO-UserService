package de.caritas.cob.userservice.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import de.caritas.cob.userservice.api.config.apiclient.ConsultingTypeServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.cache.SharedReadCache;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.consultingtypeservice.generated.ApiClient;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.ConsultingTypeControllerApi;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.BasicConsultingTypeResponseDTO;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class ConsultingTypeServiceTest {

  @InjectMocks private ConsultingTypeService consultingTypeService;

  @Mock private ConsultingTypeControllerApi consultingTypeControllerApi;

  @Mock private SecurityHeaderSupplier securityHeaderSupplier;

  @Mock private ServletRequestAttributes requestAttributes;

  @Mock private HttpServletRequest httpServletRequest;

  @Mock private Enumeration<String> headers;

  @Mock private TenantHeaderSupplier tenantHeaderSupplier;

  @Mock private SharedReadCache sharedReadCache;

  @Mock private ConsultingTypeServiceApiControllerFactory consultingTypeServiceApiControllerFactory;

  @BeforeEach
  void setUp() {
    when(consultingTypeServiceApiControllerFactory.createControllerApi())
        .thenReturn(consultingTypeControllerApi);
    when(consultingTypeControllerApi.getApiClient()).thenReturn(new ApiClient());
    lenient().when(securityHeaderSupplier.getCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    lenient()
        .when(sharedReadCache.getOrLoad(any(), anyString(), any(Class.class), any()))
        .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(3).get());
    lenient()
        .when(sharedReadCache.getOrLoadTyped(any(), anyString(), any(TypeReference.class), any()))
        .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(3).get());
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
    resetRequestAttributes();
  }

  @Test
  void getAllConsultingTypeIds_Should_Return_expectedIdList_From_BasicConsultingTypeResponseDTO() {
    when(consultingTypeServiceApiControllerFactory.createControllerApi())
        .thenReturn(consultingTypeControllerApi);

    int size = 15;
    var randomBasicConsultingTypeResponseDTOList =
        generateRandomExtendedConsultingTypeResponseDTOList(size);
    when(consultingTypeControllerApi.getBasicConsultingTypeList())
        .thenReturn(randomBasicConsultingTypeResponseDTOList);
    when(securityHeaderSupplier.getCsrfHttpHeaders()).thenReturn(new HttpHeaders());

    List<Integer> consultingTypeIds = consultingTypeService.getAllConsultingTypeIds(null);

    assertEquals(consultingTypeIds.size(), size);
    assertEquals(
        randomBasicConsultingTypeResponseDTOList.stream()
            .map(BasicConsultingTypeResponseDTO::getId)
            .collect(Collectors.toList()),
        consultingTypeIds);
  }

  private BasicConsultingTypeResponseDTO generateExtendedConsultingTypeResponseDTO(int id) {
    return new BasicConsultingTypeResponseDTO().id(id);
  }

  private List<BasicConsultingTypeResponseDTO> generateRandomExtendedConsultingTypeResponseDTOList(
      int size) {
    return new Random()
        .ints(size, Integer.MIN_VALUE, Integer.MAX_VALUE)
        .mapToObj(this::generateExtendedConsultingTypeResponseDTO)
        .collect(Collectors.toList());
  }

  @Test
  void getExtendedConsultingTypeResponseDTO_Should_callConsultingTypeController_When_idExists() {

    when(securityHeaderSupplier.getCsrfHttpHeaders()).thenReturn(new HttpHeaders());

    this.consultingTypeService.getExtendedConsultingTypeResponseDTO(1);

    verify(this.consultingTypeControllerApi, times(1)).getExtendedConsultingTypeById(1);
  }

  @Test
  void getExtendedConsultingTypeResponseDTO_ShouldUseTenantScopedSharedCacheKey() {
    TenantContext.setCurrentTenant(7L);

    consultingTypeService.getExtendedConsultingTypeResponseDTO(42);

    verify(sharedReadCache)
        .getOrLoad(
            eq(SharedReadCache.CacheName.CONSULTING_TYPE),
            eq("tenant:7:id:42"),
            eq(ExtendedConsultingTypeResponseDTO.class),
            any());
  }

  @Test
  void getAllConsultingTypeIds_ShouldPreferExplicitTenantForSharedCacheKey() {
    when(consultingTypeControllerApi.getBasicConsultingTypeList()).thenReturn(List.of());
    TenantContext.setCurrentTenant(7L);

    consultingTypeService.getAllConsultingTypeIds(8L);

    verify(sharedReadCache)
        .getOrLoadTyped(
            eq(SharedReadCache.CacheName.CONSULTING_TYPE),
            eq("tenant:8:all-ids"),
            any(TypeReference.class),
            any());
  }

  private void resetRequestAttributes() {
    RequestContextHolder.setRequestAttributes(null);
  }
}
