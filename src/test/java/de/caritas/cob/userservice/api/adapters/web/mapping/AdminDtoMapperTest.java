package de.caritas.cob.userservice.api.adapters.web.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class AdminDtoMapperTest {

  @Mock private TenantService tenantService;

  @Test
  void adminSearchResultOf_Should_NotFail_WhenTenantServiceReturnsNotFound() {
    // given
    AdminDtoMapper adminDtoMapper = new AdminDtoMapper(tenantService);
    ReflectionTestUtils.setField(adminDtoMapper, "multiTenancyEnabled", true);
    when(tenantService.getRestrictedTenantData(2L))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

    // when
    var result = adminDtoMapper.adminSearchResultOf(resultMap(), "*", 1, 10, "FIRSTNAME", "ASC");

    // then
    assertThat(result.getEmbedded()).hasSize(1);
    assertThat(result.getEmbedded().get(0).getEmbedded().getTenantId()).isEqualTo("2");
    assertThat(result.getEmbedded().get(0).getEmbedded().getTenantName()).isNull();
    assertThat(result.getEmbedded().get(0).getEmbedded().getTenantSubdomain()).isNull();
  }

  private Map<String, Object> resultMap() {
    Map<String, Object> adminMap = new HashMap<>();
    adminMap.put("id", "admin-id");
    adminMap.put("email", "admin@example.org");
    adminMap.put("firstName", "First");
    adminMap.put("lastName", "Last");
    adminMap.put("username", "admin");
    adminMap.put("createdAt", "2026-06-08T10:00:00");
    adminMap.put("updatedAt", "2026-06-08T10:00:00");
    adminMap.put("tenantId", 2L);
    adminMap.put("agencies", new ArrayList<Map<String, Object>>());

    Map<String, Object> resultMap = new HashMap<>();
    resultMap.put("admins", List.of(adminMap));
    resultMap.put("totalElements", 1);
    resultMap.put("isFirstPage", true);
    resultMap.put("isLastPage", true);
    return resultMap;
  }
}
