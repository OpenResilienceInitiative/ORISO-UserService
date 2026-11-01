package de.caritas.cob.userservice.api.admin.service.search;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

class AccountSearchRoleBoundaryTest {
  @Test
  void consultantSearchDoesNotDependOnTenantOwnerEvenWhenUnavailable() {
    var store = mock(AccountSearchStore.class);
    var agencies = mock(AgencyService.class);
    var tenants = mock(TenantService.class);
    when(store.agencyIds(any(), any(), eq(true), isNull(), eq(7L))).thenReturn(Set.of(1L));
    when(store.tenantIds(any(), any())).thenReturn(Set.of(7L));
    when(store.page(any(), any(), any())).thenReturn(Page.empty());
    when(agencies.getAgenciesWithoutCaching(anyList())).thenReturn(List.of());
    when(tenants.getRestrictedTenantData(anySet()))
        .thenThrow(new IllegalStateException("tenant offline"));
    new AccountSearch(store, new AccountSearchNames(agencies, tenants))
        .consultants("Max", 7L, null, PageRequest.of(0, 10));
    verifyNoInteractions(tenants);
    verify(store, never()).tenantIds(any(), any());
  }

  @Test
  void adminSearchDoesNotDependOnAgencyOwnerEvenWhenUnavailable() {
    var store = mock(AccountSearchStore.class);
    var agencies = mock(AgencyService.class);
    var tenants = mock(TenantService.class);
    when(store.agencyIds(any(), any(), eq(false), isNull(), eq(7L))).thenReturn(Set.of(1L));
    when(store.tenantIds(any(), any())).thenReturn(Set.of(7L));
    when(store.page(any(), any(), any())).thenReturn(Page.empty());
    when(tenants.getRestrictedTenantData(anySet())).thenReturn(List.of());
    when(agencies.getAgenciesWithoutCaching(anyList()))
        .thenThrow(new IllegalStateException("agency offline"));
    new AccountSearch(store, new AccountSearchNames(agencies, tenants))
        .admins("Max", Admin.AdminType.AGENCY, 7L, null, PageRequest.of(0, 10));
    verifyNoInteractions(agencies);
    verify(store, never()).agencyIds(any(), any(), anyBoolean(), any(), any());
  }
}
