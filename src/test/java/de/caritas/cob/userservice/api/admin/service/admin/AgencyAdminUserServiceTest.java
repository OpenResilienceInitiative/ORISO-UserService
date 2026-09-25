package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceMapper;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.SearchFilter;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AgencyAdminUserServiceTest {

  @InjectMocks private AgencyAdminUserService agencyAdminUserService;

  @Mock private AdminScope adminScope;

  @Mock private RetrieveAdminService retrieveAdminService;

  @Mock private CreateAdminService createAdminService;

  @Mock private UpdateAdminService updateAdminService;

  @Mock private DeleteAdminService deleteAdminService;

  @Mock private UserServiceMapper userServiceMapper;

  @Mock private AgencyService agencyService;

  @Mock private TenantService tenantService;

  @Mock private de.caritas.cob.userservice.api.port.out.ConsultantRepository consultantRepository;

  @Test
  void findAgencyAdminShouldExposeActiveConsultantIdentity() {
    var admin = agencyAdmin("agency-admin", 1L);
    when(retrieveAdminService.findAdmin("agency-admin", Admin.AdminType.AGENCY)).thenReturn(admin);
    when(consultantRepository.findActiveIdsByIdIn(Set.of("agency-admin")))
        .thenReturn(Set.of("agency-admin"));

    var response = agencyAdminUserService.findAgencyAdmin("agency-admin");

    assertThat(response.getEmbedded().getHasOtherIdentity()).isTrue();
    Mockito.verify(adminScope).assertMay(AdminScope.Target.admin("agency-admin"));
  }

  @Test
  void findAgencyAdminShouldReportNoActiveConsultantIdentity() {
    var admin = agencyAdmin("agency-admin", 1L);
    when(retrieveAdminService.findAdmin("agency-admin", Admin.AdminType.AGENCY)).thenReturn(admin);
    when(consultantRepository.findActiveIdsByIdIn(Set.of("agency-admin")))
        .thenReturn(Collections.emptySet());

    var response = agencyAdminUserService.findAgencyAdmin("agency-admin");

    assertThat(response.getEmbedded().getHasOtherIdentity()).isFalse();
  }

  @Test
  void findAgencyAdminsByInfix_Should_ScopeToCallerAgencies_WhenRestrictedAgencyAdmin() {
    // given
    PageRequest pageRequest = PageRequest.of(0, 10);
    Set<Long> callerAgencyIds = new LinkedHashSet<>(List.of(5L, 6L));
    when(adminScope.current()).thenReturn(new AdminScope.Agencies(1L, callerAgencyIds));
    when(retrieveAdminService.findAllByInfixFiltered(
            "*", Admin.AdminType.AGENCY, null, List.of(5L, 6L), pageRequest))
        .thenReturn(new PageImpl<>(Collections.emptyList(), pageRequest, 0));
    when(retrieveAdminService.findAllById(Mockito.anySet())).thenReturn(Collections.emptyList());
    when(retrieveAdminService.agenciesOfAdmin(Mockito.anySet()))
        .thenReturn(Collections.emptyList());
    when(agencyService.getAgenciesWithoutCaching(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    when(userServiceMapper.mapOfAdmin(
            Mockito.any(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(new HashMap<>());

    // when
    agencyAdminUserService.findAgencyAdminsByInfix("*", SearchFilter.NONE, pageRequest);

    // then: scoped query is used, the unscoped one is never called
    Mockito.verify(retrieveAdminService)
        .findAllByInfixFiltered("*", Admin.AdminType.AGENCY, null, List.of(5L, 6L), pageRequest);
    Mockito.verify(retrieveAdminService, Mockito.never())
        .findAllByInfix(Mockito.any(), Mockito.any(), Mockito.any());
  }

  @Test
  void deleteAgencyAdmin_Should_ThrowForbidden_WhenRestrictedAgencyAdminTargetsForeignAgency() {
    // given
    Mockito.doThrow(new ForbiddenException("out of reach"))
        .when(adminScope)
        .assertMay(AdminScope.Target.admin("foreign-admin"));

    // when / then
    Assertions.assertThrows(
        ForbiddenException.class, () -> agencyAdminUserService.deleteAgencyAdmin("foreign-admin"));
    Mockito.verify(deleteAdminService, Mockito.never()).deleteAgencyAdmin(Mockito.any());
  }

  /**
   * #968: a tenant admin (not restricted agency admin, not platform admin) must not act on agency
   * admins of other tenants via the by-id endpoints — mirrors the search-side tenant scoping.
   */
  @Test
  void deleteAgencyAdmin_Should_ThrowForbidden_WhenTenantAdminTargetsForeignTenant() {
    Mockito.doThrow(new ForbiddenException("out of reach"))
        .when(adminScope)
        .assertMay(AdminScope.Target.admin("foreign-agency-admin"));

    Assertions.assertThrows(
        ForbiddenException.class,
        () -> agencyAdminUserService.deleteAgencyAdmin("foreign-agency-admin"));
    Mockito.verify(deleteAdminService, Mockito.never()).deleteAgencyAdmin(Mockito.any());
  }

  @Test
  void deleteAgencyAdmin_Should_CheckTheScopeBeforeDeleting() {
    agencyAdminUserService.deleteAgencyAdmin("agency-admin");

    var order = Mockito.inOrder(adminScope, deleteAdminService);
    order.verify(adminScope).assertMay(AdminScope.Target.admin("agency-admin"));
    order.verify(deleteAdminService).deleteAgencyAdmin("agency-admin");
  }

  @Test
  void findAgencyAdminsByInfix_Should_NotUsePerTenantLookups() {
    // given
    PageRequest pageRequest = PageRequest.of(0, 10);
    Admin.AdminBase firstAdminBase = adminBase("agency-admin-1", 1L);
    Admin.AdminBase secondAdminBase = adminBase("agency-admin-2", 1L);
    Admin.AdminBase thirdAdminBase = adminBase("agency-admin-3", 2L);
    Page<Admin.AdminBase> adminsPage =
        new PageImpl<>(
            Arrays.asList(firstAdminBase, secondAdminBase, thirdAdminBase), pageRequest, 3);
    Admin firstAgencyAdmin = agencyAdmin("agency-admin-1", 1L);
    Admin secondAgencyAdmin = agencyAdmin("agency-admin-2", 1L);
    Admin thirdAgencyAdmin = agencyAdmin("agency-admin-3", 2L);
    List<Admin> fullAdmins = Arrays.asList(firstAgencyAdmin, secondAgencyAdmin, thirdAgencyAdmin);
    when(adminScope.current()).thenReturn(new AdminScope.Platform());
    when(retrieveAdminService.findAllByInfixFiltered(
            "*", Admin.AdminType.AGENCY, null, null, pageRequest))
        .thenReturn(adminsPage);
    when(retrieveAdminService.findAllById(Mockito.anySet())).thenReturn(fullAdmins);
    when(retrieveAdminService.agenciesOfAdmin(Mockito.anySet()))
        .thenReturn(Collections.emptyList());
    when(agencyService.getAgenciesWithoutCaching(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    when(tenantService.getRestrictedTenantData(Set.of(1L, 2L)))
        .thenReturn(List.of(new RestrictedTenantDTO().id(1L).name("Known tenant")));
    when(userServiceMapper.mapOfAdmin(
            Mockito.any(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(new HashMap<>());

    // when
    agencyAdminUserService.findAgencyAdminsByInfix("*", SearchFilter.NONE, pageRequest);

    // then
    ArgumentCaptor<Map<Long, String>> tenantNameMapCaptor = ArgumentCaptor.forClass(Map.class);
    Mockito.verify(userServiceMapper)
        .mapOfAdmin(
            Mockito.eq(adminsPage),
            Mockito.eq(fullAdmins),
            Mockito.anyList(),
            Mockito.anyList(),
            tenantNameMapCaptor.capture(),
            Mockito.any());
    Assertions.assertEquals("Known tenant", tenantNameMapCaptor.getValue().get(1L));
    Assertions.assertFalse(tenantNameMapCaptor.getValue().containsKey(2L));
    Mockito.verify(tenantService).getRestrictedTenantData(Set.of(1L, 2L));
    Mockito.verify(tenantService, Mockito.never()).getRestrictedTenantData(Mockito.anyLong());
  }

  /**
   * #968: a plain tenant admin (no restricted-agency privileges, not platform admin) querying
   * /useradmin/agencyadmins/search must only see agency admins of their own tenant. Before the fix
   * the same call returned agency admins of every tenant, mirroring the tenant-admin leak.
   */
  @Test
  void findAgencyAdminsByInfix_Should_ScopeToCallerTenant_ForTenantAdmin() {
    PageRequest pageRequest = PageRequest.of(0, 10);
    when(adminScope.current()).thenReturn(new AdminScope.Tenant(9L));
    when(retrieveAdminService.findAllByInfixFiltered(
            "*", Admin.AdminType.AGENCY, 9L, null, pageRequest))
        .thenReturn(new PageImpl<>(Collections.emptyList(), pageRequest, 0));
    when(retrieveAdminService.findAllById(Mockito.anySet())).thenReturn(Collections.emptyList());
    when(retrieveAdminService.agenciesOfAdmin(Mockito.anySet()))
        .thenReturn(Collections.emptyList());
    when(agencyService.getAgenciesWithoutCaching(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    when(userServiceMapper.mapOfAdmin(
            Mockito.any(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(new HashMap<>());

    agencyAdminUserService.findAgencyAdminsByInfix("*", SearchFilter.NONE, pageRequest);

    Mockito.verify(retrieveAdminService)
        .findAllByInfixFiltered("*", Admin.AdminType.AGENCY, 9L, null, pageRequest);
    Mockito.verify(retrieveAdminService, Mockito.never())
        .findAllByInfix(Mockito.anyString(), Mockito.any(), Mockito.any(PageRequest.class));
    Mockito.verify(retrieveAdminService, Mockito.never())
        .findAllByInfixScopedToTenant(
            Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(PageRequest.class));
  }

  /**
   * Fail-closed mirror of the tenant-admin search: a tenant-bound caller without a resolvable
   * tenant is refused by the scope and the search never queries — never the unscoped list (#968).
   */
  @Test
  void findAgencyAdminsByInfix_Should_FailClosed_WhenTenantAdminHasNullTenant() {
    PageRequest pageRequest = PageRequest.of(0, 10);
    when(adminScope.current()).thenThrow(new ForbiddenException("no tenant"));

    Assertions.assertThrows(
        ForbiddenException.class,
        () -> agencyAdminUserService.findAgencyAdminsByInfix("*", SearchFilter.NONE, pageRequest));

    Mockito.verifyNoInteractions(retrieveAdminService);
  }

  @Test
  void findAgencyAdminsByInfix_Should_ReturnEmpty_WhenTenantAdminFiltersForeignTenant() {
    PageRequest pageRequest = PageRequest.of(0, 10);
    when(adminScope.current()).thenReturn(new AdminScope.Tenant(9L));
    givenEmptyAdminMapping();

    agencyAdminUserService.findAgencyAdminsByInfix(
        "*", new SearchFilter(10L, null), pageRequest);

    Mockito.verify(retrieveAdminService, Mockito.never())
        .findAllByInfixFiltered(
            Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
  }

  @Test
  void findAgencyAdminsByInfix_Should_KeepOwnTenant_WhenTenantAdminFiltersAgencies() {
    PageRequest pageRequest = PageRequest.of(0, 10);
    when(adminScope.current()).thenReturn(new AdminScope.Tenant(9L));
    when(retrieveAdminService.findAllByInfixFiltered(
            "*", Admin.AdminType.AGENCY, 9L, List.of(3L), pageRequest))
        .thenReturn(new PageImpl<>(Collections.emptyList(), pageRequest, 0));
    givenEmptyAdminMapping();

    agencyAdminUserService.findAgencyAdminsByInfix(
        "*", new SearchFilter(9L, List.of(3L)), pageRequest);

    Mockito.verify(retrieveAdminService)
        .findAllByInfixFiltered("*", Admin.AdminType.AGENCY, 9L, List.of(3L), pageRequest);
  }

  @Test
  void findAgencyAdminsByInfix_Should_IntersectRequestedAgencies_ForAgencyAdmin() {
    PageRequest pageRequest = PageRequest.of(0, 10);
    when(adminScope.current())
        .thenReturn(new AdminScope.Agencies(1L, new LinkedHashSet<>(List.of(5L, 6L))));
    when(retrieveAdminService.findAllByInfixFiltered(
            "*", Admin.AdminType.AGENCY, null, List.of(6L), pageRequest))
        .thenReturn(new PageImpl<>(Collections.emptyList(), pageRequest, 0));
    givenEmptyAdminMapping();

    agencyAdminUserService.findAgencyAdminsByInfix(
        "*", new SearchFilter(null, List.of(6L, 7L)), pageRequest);

    Mockito.verify(retrieveAdminService)
        .findAllByInfixFiltered("*", Admin.AdminType.AGENCY, null, List.of(6L), pageRequest);
  }

  @Test
  void findAgencyAdminsByInfix_Should_PassFilterAsGiven_ForPlatformAdmin() {
    PageRequest pageRequest = PageRequest.of(0, 10);
    when(adminScope.current()).thenReturn(new AdminScope.Platform());
    when(retrieveAdminService.findAllByInfixFiltered(
            "*", Admin.AdminType.AGENCY, 4L, List.of(3L), pageRequest))
        .thenReturn(new PageImpl<>(Collections.emptyList(), pageRequest, 0));
    givenEmptyAdminMapping();

    agencyAdminUserService.findAgencyAdminsByInfix(
        "*", new SearchFilter(4L, List.of(3L)), pageRequest);

    Mockito.verify(retrieveAdminService)
        .findAllByInfixFiltered("*", Admin.AdminType.AGENCY, 4L, List.of(3L), pageRequest);
  }

  private void givenEmptyAdminMapping() {
    when(retrieveAdminService.findAllById(Mockito.anySet())).thenReturn(Collections.emptyList());
    when(retrieveAdminService.agenciesOfAdmin(Mockito.anySet()))
        .thenReturn(Collections.emptyList());
    when(agencyService.getAgenciesWithoutCaching(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    when(userServiceMapper.mapOfAdmin(
            Mockito.any(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(new HashMap<>());
  }

  @Test
  void findAgencyAdminsByInfix_Should_NotScope_ForPlatformAdmin() {
    PageRequest pageRequest = PageRequest.of(0, 10);
    when(adminScope.current()).thenReturn(new AdminScope.Platform());
    when(retrieveAdminService.findAllByInfixFiltered(
            "*", Admin.AdminType.AGENCY, null, null, pageRequest))
        .thenReturn(new PageImpl<>(Collections.emptyList(), pageRequest, 0));
    when(retrieveAdminService.findAllById(Mockito.anySet())).thenReturn(Collections.emptyList());
    when(retrieveAdminService.agenciesOfAdmin(Mockito.anySet()))
        .thenReturn(Collections.emptyList());
    when(agencyService.getAgenciesWithoutCaching(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    when(userServiceMapper.mapOfAdmin(
            Mockito.any(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.anyList(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(new HashMap<>());

    agencyAdminUserService.findAgencyAdminsByInfix("*", SearchFilter.NONE, pageRequest);

    Mockito.verify(retrieveAdminService)
        .findAllByInfixFiltered("*", Admin.AdminType.AGENCY, null, null, pageRequest);
    Mockito.verify(retrieveAdminService, Mockito.never())
        .findAllByInfixScopedToTenant(
            Mockito.anyString(), Mockito.any(), Mockito.anyLong(), Mockito.any(PageRequest.class));
  }

  private Admin agencyAdmin(String id, Long tenantId) {
    Admin admin = new Admin();
    admin.setId(id);
    admin.setType(Admin.AdminType.AGENCY);
    admin.setTenantId(tenantId);
    return admin;
  }

  private Admin.AdminBase adminBase(String id, Long tenantId) {
    return new Admin.AdminBase() {
      @Override
      public String getId() {
        return id;
      }

      @Override
      public String getFirstName() {
        return "First";
      }

      @Override
      public String getLastName() {
        return "Last";
      }

      @Override
      public String getEmail() {
        return id + "@example.org";
      }

      @Override
      public Long getTenantId() {
        return tenantId;
      }

      @Override
      public Admin.AdminType getType() {
        return Admin.AdminType.AGENCY;
      }

      @Override
      public LocalDateTime getUpdateDate() {
        return LocalDateTime.now();
      }
    };
  }
}
