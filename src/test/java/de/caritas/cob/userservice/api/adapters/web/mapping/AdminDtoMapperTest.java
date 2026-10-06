package de.caritas.cob.userservice.api.adapters.web.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.admin.AccountLoginStatusService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.SearchFilter;
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
  @Mock private AccountLoginStatusService accountLoginStatusService;

  @Test
  @SuppressWarnings("unchecked")
  void adminSearchResultOf_Should_StopStatusReadsAfterOneOutage_AndKeepBothRows() {
    var lookup =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.port.out.IdentityAccountStatusLookup.class);
    var mapper = new AdminDtoMapper(tenantService);
    ReflectionTestUtils.setField(
        mapper, "accountLoginStatusService", new AccountLoginStatusService(lookup));
    when(lookup.findEnabledById("admin-id"))
        .thenThrow(new jakarta.ws.rs.ProcessingException("timeout"));
    var source = resultMap();
    var first = (Map<String, Object>) ((List<?>) source.get("admins")).get(0);
    var second = new HashMap<>(first);
    second.put("id", "second-admin");
    source.put("admins", List.of(first, second));
    source.put("totalElements", 2);

    var result = mapper.adminSearchResultOf(source, "*", 1, 10, "FIRSTNAME", "ASC");

    assertThat(result.getEmbedded()).hasSize(2);
    assertThat(result.getTotal()).isEqualTo(2);
    assertThat(result.getEmbedded())
        .allSatisfy(row -> assertThat(row.getEmbedded().getActive()).isNull());
    org.mockito.Mockito.verify(lookup).findEnabledById("admin-id");
    org.mockito.Mockito.verifyNoMoreInteractions(lookup);
  }

  @Test
  void adminSearchResultOf_Should_ExposeDisabledLogin_ForTheScopedRow() {
    var status = org.mockito.Mockito.mock(AccountLoginStatusService.class);
    var mapper = new AdminDtoMapper(tenantService);
    ReflectionTestUtils.setField(mapper, "accountLoginStatusService", status);
    when(status.activeByIds(List.of("admin-id"))).thenReturn(Map.of("admin-id", false));

    var result = mapper.adminSearchResultOf(resultMap(), "*", 1, 10, "FIRSTNAME", "ASC");

    assertThat(result.getEmbedded().get(0).getEmbedded().getActive()).isFalse();
    org.mockito.Mockito.verify(status).activeByIds(List.of("admin-id"));
    org.mockito.Mockito.verifyNoMoreInteractions(status);
  }

  @Test
  void adminSearchResultOf_Should_NotFail_WhenTenantServiceReturnsNotFound() {
    // given
    AdminDtoMapper adminDtoMapper = new AdminDtoMapper(tenantService);
    ReflectionTestUtils.setField(
        adminDtoMapper, "accountLoginStatusService", accountLoginStatusService);
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
    // publicName used to be dead scaffolding hardcoded to null (#996) — it is the full name now.
    assertThat(result.getEmbedded().get(0).getEmbedded().getPublicName()).isEqualTo("First Last");
    assertThat(result.getEmbedded().get(0).getEmbedded().getRoleInOrg()).isEqualTo("Tenant Admin");
    assertThat(result.getEmbedded().get(0).getEmbedded().getVacated()).isFalse();
    assertThat(result.getEmbedded().get(0).getEmbedded().getAdminRights()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void adminSearchResultOf_Should_FallBackToUsernameForPublicName_When_NamesAreBlank() {
    AdminDtoMapper adminDtoMapper = new AdminDtoMapper(tenantService);
    ReflectionTestUtils.setField(
        adminDtoMapper, "accountLoginStatusService", accountLoginStatusService);
    ReflectionTestUtils.setField(adminDtoMapper, "multiTenancyEnabled", false);
    var resultMap = resultMap();
    var adminMap = (Map<String, Object>) ((List<?>) resultMap.get("admins")).get(0);
    adminMap.put("firstName", "  ");
    adminMap.put("lastName", null);

    var result = adminDtoMapper.adminSearchResultOf(resultMap, "*", 1, 10, "FIRSTNAME", "ASC");

    assertThat(result.getEmbedded().get(0).getEmbedded().getPublicName()).isEqualTo("admin");
  }

  @Test
  void adminSearchResultOf_Should_MapSupportAdminRoleInOrg() {
    // given
    AdminDtoMapper adminDtoMapper = new AdminDtoMapper(tenantService);
    ReflectionTestUtils.setField(
        adminDtoMapper, "accountLoginStatusService", accountLoginStatusService);
    ReflectionTestUtils.setField(adminDtoMapper, "multiTenancyEnabled", false);
    var resultMap = resultMap();
    ((Map<String, Object>) ((List<?>) resultMap.get("admins")).get(0))
        .put("type", Admin.AdminType.SUPPORT);

    // when
    var result = adminDtoMapper.adminSearchResultOf(resultMap, "*", 1, 10, "FIRSTNAME", "ASC");

    // then
    assertThat(result.getEmbedded().get(0).getEmbedded().getRoleInOrg()).isEqualTo("Support Admin");
  }

  @Test
  void adminSearchResultOf_Should_KeepFilters_In_PageLinks() {
    AdminDtoMapper adminDtoMapper = new AdminDtoMapper(tenantService);
    ReflectionTestUtils.setField(
        adminDtoMapper, "accountLoginStatusService", accountLoginStatusService);
    var resultMap = resultMap();
    resultMap.put("isFirstPage", false);
    resultMap.put("isLastPage", false);

    var result =
        adminDtoMapper.adminSearchResultOf(
            resultMap, "*", 2, 10, "FIRSTNAME", "ASC", new SearchFilter(4L, List.of(3L)));

    for (var link :
        List.of(
            result.getLinks().getSelf(),
            result.getLinks().getPrevious(),
            result.getLinks().getNext())) {
      assertThat(link.getHref()).contains("tenantId=4").contains("agencyId=3");
      assertThat(link.getTemplated()).isFalse();
    }
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
    adminMap.put("type", Admin.AdminType.TENANT);
    adminMap.put("agencies", new ArrayList<Map<String, Object>>());

    Map<String, Object> resultMap = new HashMap<>();
    resultMap.put("admins", List.of(adminMap));
    resultMap.put("totalElements", 1);
    resultMap.put("isFirstPage", true);
    resultMap.put("isLastPage", true);
    return resultMap;
  }
}
